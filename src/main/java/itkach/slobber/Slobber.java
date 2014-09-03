package itkach.slobber;

import itkach.slob.Slob;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class Slobber implements Container {


    static abstract class GETContainer implements Container {

        @Override
        public void handle(Request req, Response resp) {
            long time = System.currentTimeMillis();
            resp.setValue("Server", "Slobber/1.0 (Simple 5.1.6)");
            resp.setDate("Date", time);
            resp.setValue("Access-Control-Allow-Origin", req.getValue("Origin"));
            try {
                if (req.getMethod().equals("GET")) {
                    GET(req, resp);
                }
                else {
                    resp.setStatus(Status.METHOD_NOT_ALLOWED);
                    resp.setValue("Content-Type", "text/plain");
                    resp.getPrintStream().printf("Method %s is not allowed", req.getMethod());
                }
            }
            catch (Exception e) {
                e.printStackTrace();
                resp.setValue("Content-Type", "text/plain");
                resp.setCode(500);
                PrintStream out = null;
                try {
                    out = resp.getPrintStream();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                if (out != null && !out.checkError()) {
                    e.printStackTrace(out);
                }
            }
            try {
                resp.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        abstract protected void GET(Request req, Response resp) throws Exception;

    }

    private List<Slob> slobs = Collections.emptyList();
    private Map<String, Slob> slobMap = new HashMap<String, Slob>();
    private Map<String, Container> handlers = new HashMap<String, Container>();
    private ObjectMapper json = new ObjectMapper();


    public Slob getSlob(String slobId) {
        return slobMap.get(slobId);
    }

    public Slob[] getSlobs() {
        return slobs.toArray(new Slob[slobs.size()]);
    }

    public void setSlobs(List<Slob> newSlobs) {
        slobMap.clear();
        if (newSlobs == null) {
            newSlobs = Collections.emptyList();
        }
        this.slobs = newSlobs;
        for (Slob s : this.slobs) {
            slobMap.put(s.getId().toString(), s);
        }
    }

    public String getSlobURI(String slobId) {
        Slob slob = getSlob(slobId);
        return getURI(slob);
    }

    public String getURI(Slob slob) {
        Map<String, String> tags = slob.getTags();
        String uri = tags.get("uri");
        if (uri == null) {
            uri = "slob:" + slob.getId();
        }
        return uri;
    }

    public Slob findSlob(String slobIdOrUri) {
        Slob slob = getSlob(slobIdOrUri);
        if (slob == null) {
            slob = findSlobByURI(slobIdOrUri);
        }
        return slob;
    }

    public Slob findSlobByURI(String slobURI) {
        for (Slob s : slobs) {
            if (getURI(s).equals(slobURI)) {
                return s;
            }
        }
        return null;
    }

    public Slobber() {

        json.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        json.configure(SerializationFeature.INDENT_OUTPUT, true);

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
                    protected void GET(Request req, Response resp)
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
                        OutputStream out = resp.getOutputStream();
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
            public void GET(Request request, Response response) throws Exception{
                Query q = request.getQuery();
                String key = q.get("key");
                if (key == null) {
                    notFound(response);
                    return;
                }
                Iterator<Slob.Blob> result = Slob.find(key, getSlobs());
                List<Map<String, String>> items = new ArrayList<Map<String, String>>();
                while (result.hasNext()) {
                    Slob.Blob b = result.next();
                    Map<String, String> item = new HashMap<String, String>();
                    item.put("url", mkContentURL(b));
                    item.put("label", b.key);
                    items.add(item);
                }
                response.setValue("Content-Type", "application/json");
                OutputStream out = response.getOutputStream();
                OutputStreamWriter os = new OutputStreamWriter(out, "UTF8");
                json.writeValue(os, items);
                os.close();
            }
        });

        handlers.put("slob", new GETContainer() {

            @Override
            protected void GET(Request req, Response resp)
                    throws Exception {
                Query q = req.getQuery();
                String ifNoneMatch = req.getValue("If-None-Match");
                String blobId = q.get("blob");

                String[] pathSegments = req.getPath().getSegments();

                String key = q.get("key");
                if (pathSegments.length == 3) {
                    key = pathSegments[2];
                }

                String slobIdOrUri = null;
                if (pathSegments.length >= 2) {
                    slobIdOrUri = pathSegments[1];
                }

                Slob slob = getSlob(slobIdOrUri);
                if (slob == null) {
                    slob = findSlob(slobIdOrUri);
                }

                if (slob == null) {
                    notFound(resp);
                    return;
                }

                if (blobId != null) {
                    if (ifNoneMatch != null) {
                        if (mkETag(slob.getId(), blobId).equals(ifNoneMatch)) {
                            resp.setStatus(Status.NOT_MODIFIED);
                            return;
                        }
                    }
                    Slob.ContentReader reader = slob.get(blobId);
                    serveContent(resp, slob, blobId, reader);
                    return;
                }

                if (key != null) {
                    if (ifNoneMatch != null) {
                        if (mkETag(slob.getId(), key).equals(ifNoneMatch)) {
                            resp.setStatus(Status.NOT_MODIFIED);
                            return;
                        }
                    }
                }

                Iterator<Slob.Blob> result = Slob.find(key, slob);
                if (result.hasNext()) {
                    Slob.Blob blob = result.next();
                    serveContent(resp, slob, key, blob);
                    return;
                }

                notFound(resp);
            }
        });
    }

    private void serveContent(Response resp, Slob slob, String blobIdOrKey,
                              Slob.ContentReader reader) throws IOException {
        setHeaders(resp, reader.getContentType(), slob.getId(), blobIdOrKey);
        ByteBuffer bytes = reader.getContent();
        resp.getByteChannel().write(bytes);
    }

    public Server start(String addrStr, int port) throws IOException {
        Server server = new ContainerServer(this, 16);
        Connection connection = new SocketConnection(server);
        SocketAddress address = new InetSocketAddress(InetAddress.getByName(addrStr), 8013);
        connection.connect(address);
        return server;
    }

    private void setHeaders(Response resp, String contentType, UUID ownerId, String blobIdOrKey) throws IOException {
        resp.setValue("Content-Type", contentType);
        resp.setValue("ETag", mkETag(ownerId, URLEncoder.encode(blobIdOrKey, "UTF-8")));
    }

    private void notFound(Response resp) throws IOException {
        resp.setStatus(Status.NOT_FOUND);
        resp.setValue("Content-Type", "text/plain");
        PrintStream body = resp.getPrintStream();
        body.printf("Not found");
    }

    public static String mkContentURL(Slob.Blob b) {
        try {
            return String.format("/slob/%s/%s?blob=%s#%s",
                    b.owner.getId(),
                    URLEncoder.encode(b.key, "UTF-8"),
                    b.id, b.fragment);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private String mkETag(UUID ownerId, String blobId) {
        return mkETag(ownerId.toString(), blobId);
    }

    private String mkETag(String ownerId, String blobId) {
        return String.format("\"%s/%s\"", ownerId, blobId);
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
        System.out.println(String.format("Listening at %s:%s", addr, port));
    }
 }