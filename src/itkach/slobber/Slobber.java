package itkach.slobber;

import itkach.slob.Slob;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.simpleframework.http.Path;
import org.simpleframework.http.Query;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;
import org.simpleframework.http.core.ContainerServer;
import org.simpleframework.http.parse.AddressParser;
import org.simpleframework.transport.Server;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;

public class Slobber implements Container {

    static abstract class GETContainer implements Container {

        @Override
        public void handle(Request req, Response resp) {
            PrintStream out = null;
            long time = System.currentTimeMillis();
            resp.setValue("Server", "Slobber/1.0 (Simple 5.1.6)");
            resp.setDate("Date", time);
            resp.setValue("Access-Control-Allow-Origin", req.getValue("Origin"));
            try {
                out = resp.getPrintStream();
                if (req.getMethod().equals("GET")) {
                    GET(req, resp, out);
                }
                else {
                    resp.setStatus(Status.METHOD_NOT_ALLOWED);
                    resp.setValue("Content-Type", "text/plain");
                    out.printf("Method %s is not allowed", req.getMethod());
                }
            }
            catch (Exception e) {
                e.printStackTrace();
                resp.setValue("Content-Type", "text/plain");
                resp.setCode(500);
                if (out != null && !out.checkError()) {
                    e.printStackTrace(out);
                }
            }
            out.close();
        }

        abstract protected void GET(Request req, Response resp, PrintStream out) throws Exception;

    }

    private List<Slob> slobs = Collections.emptyList();
    private Map<String, Slob> slobMap = new HashMap<String, Slob>();
    private Map<String, Container> handlers = new HashMap<String, Container>();


    public Slob getSlob(String slobId) {
        return slobMap.get(slobId);
    }

    public List<Slob> getSlobs() {
        return slobs;
    }

    public void setSlobs(List<Slob> newSlobs) {
        if (this.slobs != null) {
            for (Slob s : slobs) {
                try {
                    s.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            slobMap.clear();
        }
        if (newSlobs == null) {
            newSlobs = Collections.emptyList();
        }
        this.slobs = newSlobs;
        for (Slob s : this.slobs) {
            slobMap.put(s.getId().toString(), s);
        }
    }

    public Slobber() {

        Properties sysProps = System.getProperties();

        Set<Entry<Object, Object>> propEntries = sysProps.entrySet();

        for (Entry <Object, Object> entry : propEntries) {
            String key = entry.getKey().toString();
            if (key.startsWith("slobber.static.")) {
                final String staticDirValue = entry.getValue().toString();
                String[] parts = key.split("\\.", 3);
                final String staticMountPoint = parts[2];
                final String staticDir;
                if (!staticDirValue.endsWith(File.separator)) {
                    staticDir = staticDirValue + File.separator;
                }
                else {
                    staticDir = staticDirValue;
                }
                System.out.println(String.format("Mounting %s at /%s", staticDir, staticMountPoint));
                handlers.put(staticMountPoint, new GETContainer() {

                    Map<String, String> mimeTypes = new HashMap<String, String>();

                    {
                        mimeTypes.put("html", "text/html");
                        mimeTypes.put("js", "application/javascript");
                        mimeTypes.put("css", "text/css");
                    }

                    @Override
                    protected void GET(Request req, Response resp, PrintStream out)
                            throws Exception {
                        Path path = req.getPath();
                        String extension =  path.getExtension();
                        StringBuilder fsPath = new StringBuilder();
                        String[] pathSegments = path.getSegments();
                        for (int i = 1; i < pathSegments.length; i++) {
                            if (fsPath.length() > 0) {
                                fsPath.append("/");
                            }
                            fsPath.append(pathSegments[i]);
                        }
                        File resourceFile = new File(staticDir, fsPath.toString());
                        if (resourceFile.isDirectory()) {
                            if (!path.getPath().endsWith("/")) {
                                resp.setValue("Location", path.getPath() + "/");
                                resp.setStatus(Status.MOVED_PERMANENTLY);
                                return;
                            }
                            resourceFile = new File(resourceFile, "index.html");
                            extension = "html";
                        }
                        if (!resourceFile.exists()) {
                            notFound(resp);
                            return;
                        }

                        String mimeType = mimeTypes.get(extension);
                        InputStream is = new FileInputStream(resourceFile);
                        if (mimeType != null) {
                            resp.setValue("Content-Type", mimeType);
                        }
                        while (true) {
                            byte [] buf = new byte[16384];
                            int readCount = is.read(buf);
                            if (readCount == -1) {
                                break;
                            }
                            out.write(buf, 0, readCount);
                        }
                        is.close();
                    }
                });
            }
        }

        handlers.put("find", new GETContainer() {
            @Override
            public void GET(Request request, Response response, PrintStream out) throws Exception{
                Query q = request.getQuery();
                String key = q.get("key");
                if (key == null) {
                    notFound(response);
                    return;
                }
                Iterator<Slob.Blob> result = Slob.find(key, getSlobs());
                JsonArray json = new JsonArray();
                while (result.hasNext()) {
                    Slob.Blob b = result.next();
                    JsonObject item = new JsonObject();
                    item.add("url", mkContentURL(b));
                    item.add("label", b.key);
                    json.add(item);
                }
                response.setValue("Content-Type", "application/json");
                OutputStreamWriter os = new OutputStreamWriter(out, "UTF8");
                json.writeTo(os);
                os.close();
            }
        });

        handlers.put("content", new GETContainer() {

            @Override
            protected void GET(Request req, Response resp, PrintStream out)
                    throws Exception {
                Query q = req.getQuery();
                String ifNoneMatch = req.getValue("If-None-Match");
                String slobId = q.get("slob");
                String blobId = q.get("blob");
                if (ifNoneMatch != null && slobId != null && blobId != null &&
                        mkETag(slobId, blobId).equals(ifNoneMatch)) {
                    resp.setStatus(Status.NOT_MODIFIED);
                    return;
                }
                if (slobId != null && blobId != null) {
                    Slob slob = getSlob(slobId);
                    if (slob == null) {
                        notFound(resp);
                        return;
                    }
                    Slob.ContentReader content = slob.get(blobId);
                    setHeaders(resp, content, slob.getId(), blobId);
                    out.write(content.getContent());
                    return;
                }

                String referer = req.getValue("Referer");
                AddressParser refererParser = new AddressParser(referer);
                String referringSlobId = refererParser.getQuery().get("slob");
                String key = q.get("key");
                String[] pathSegments = req.getPath().getSegments();
                if (pathSegments.length == 2) {
                    key = pathSegments[1];
                    Iterator<Slob.Blob> result = null;
                    if (referringSlobId != null) {
                        Slob referringSlob = slobMap.get(referringSlobId);
                        if (referringSlob != null) {
                            result = Slob.find(key, Arrays.asList(new Slob[]{referringSlob}));
                            if (result.hasNext()) {
                                resp.setValue("Location", mkContentURL(result.next())) ;
                                resp.setStatus(Status.SEE_OTHER);
                                return;
                            }
                        }
                    }
                    if (result == null || !result.hasNext()) {
                        result = Slob.find(key, getSlobs());
                        if (!result.hasNext()) {
                            notFound(resp);
                            return;
                        }
                    }
                    resp.setValue("Location", mkContentURL(result.next())) ;
                    resp.setStatus(Status.SEE_OTHER);
                    return;
                }
                notFound(resp);
            }
        });
    }

    public Server start(String addrStr, int port) throws IOException {
        Server server = new ContainerServer(this);
        Connection connection = new SocketConnection(server);
        SocketAddress address = new InetSocketAddress(InetAddress.getByName(addrStr), 8013);
        connection.connect(address);
        return server;
    }

    private void setHeaders(Response resp, Slob.ContentReader c, UUID ownerId, String blobId) throws IOException {
        resp.setValue("Content-Type", c.getContentType());
        resp.setValue("ETag", mkETag(ownerId, blobId));
    }

    private void notFound(Response resp) throws IOException {
        resp.setStatus(Status.NOT_FOUND);
        resp.setValue("Content-Type", "text/plain");
        PrintStream body = resp.getPrintStream();
        body.printf("Not found");
    }

    private String mkContentURL(Slob.Blob b) {
        return String.format("/content/?slob=%s&blob=%s", b.owner.getId(), b.id);
    }

    private String mkETag(UUID ownerId, String blobId) {
        return mkETag(ownerId.toString(), blobId);
    }

    private String mkETag(String ownerId, String blobId) {
        return String.format("\"%s-%s\"", ownerId, blobId);
    }

    public void handle(Request req, Response resp) {
        String[] pathSegments = req.getPath().getSegments();
        if (pathSegments.length > 0) {
            String resourceName = pathSegments[0];
            Container handler = this.handlers.get(resourceName);
            if (handler == null) {
                try {
                    notFound(resp);
                    resp.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            else {
                handler.handle(req, resp);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Slob[] slobs = new Slob[args.length];
        for (int i = 0; i < slobs.length; i++) {
            slobs[i] = new Slob(new File(args[i]));
        }
        int port = Integer.parseInt(System.getProperty("slobber.port", "8013"));
        String addr = System.getProperty("slobber.host", "127.0.0.1");
        Slobber slobber = new Slobber();
        slobber.setSlobs(Arrays.asList(slobs));
        slobber.start(addr, port);
    }
 }