package itkach.slobber;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ibm.icu.text.Collator;

import org.simpleframework.http.Path;
import org.simpleframework.http.Query;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;
import org.simpleframework.http.core.ContainerServer;
import org.simpleframework.http.parse.ContentTypeParser;
import org.simpleframework.transport.Server;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import itkach.slob.Slob;

public class Slobber implements Container {

    final static Logger L = Logger.getLogger(Slobber.class.getName());

    private final Random random;

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

    static Map<String, String> MimeTypes = new HashMap<String, String>();

    static {
        MimeTypes.put("html", "text/html");
        MimeTypes.put("js", "application/javascript");
        MimeTypes.put("css", "text/css");
        MimeTypes.put("json", "application/json");
        MimeTypes.put("woff", "application/font-woff");
        MimeTypes.put("svg", "image/svg+xml");
        MimeTypes.put("png", "image/png");
        MimeTypes.put("jpg", "image/jpeg");
        MimeTypes.put("jpeg", "image/jpeg");
        MimeTypes.put("ttf", "application/x-font-ttf");
        MimeTypes.put("otf", "application/x-font-opentype");
        MimeTypes.put("ico", "image/x-icon");
    }

    static void pipe(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            out.write(b);
        }
    }

    static class StaticContainer extends GETContainer {

        private File staticRes;

        StaticContainer(String staticName) {
            this.staticRes = new File(staticName);
        }

        @Override
        protected void GET(Request req, Response resp)
        throws Exception {
            if (!staticRes.exists()) {
                notFound(resp);
                return;
            }
            File resourceFile;
            Path path = req.getPath();
            String extension =  path.getExtension();
            if (staticRes.isFile()){
                resourceFile = staticRes;
            }
            else {
                StringBuilder fsPath = new StringBuilder();
                String[] pathSegments = path.getSegments();
                for (int i = 1; i < pathSegments.length; i++) {
                    if (fsPath.length() > 0) {
                        fsPath.append("/");
                    }
                    fsPath.append(pathSegments[i]);
                }
                resourceFile = new File(staticRes, fsPath.toString());
            }
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

            String mimeType = MimeTypes.get(extension);
            InputStream is = new FileInputStream(resourceFile);
            if (mimeType != null) {
                resp.setValue("Content-Type", mimeType);
            }
            pipe(is, resp.getOutputStream());
            is.close();
        }
    }

    static class ResourceContainer extends GETContainer {

        @Override
        protected void GET(Request req, Response resp)
                throws Exception {
            Path path = req.getPath();
            L.fine("Got request: " + path);
            String extension =  path.getExtension();
            String resource = path.toString().substring(1);
            if (resource.equals("")) {
                resource = "index.html";
            }
            InputStream is = ResourceContainer.class.getClassLoader().getResourceAsStream(resource);
            if (is == null) {
                notFound(resp);
                return;
            }
            String mimeType = MimeTypes.get(extension);
            if (mimeType != null) {
                resp.setValue("Content-Type", mimeType);
            }
            resp.setValue("Cache-Control", "public, max-age=86400");
            pipe(is, resp.getOutputStream());
            is.close();
        }
    }


    private List<Slob> slobs = Collections.emptyList();
    private Map<String, Slob> slobMap = new HashMap<String, Slob>();
    private Map<String, Container> handlers = new HashMap<String, Container>();
    private Container defaultResourceContainer = new ResourceContainer();
    private ObjectMapper json = new ObjectMapper();

    private Comparator<Slob> createTimeComparator = new Comparator<Slob>() {
        @Override
        public int compare(Slob s1, Slob s2) {
            String ts1 = s1.getTags().get("created.at");
            String ts2 = s2.getTags().get("created.at");
            if (ts2 == null) ts2 = "";
            if (ts1 == null) ts1 = "";
            return ts2.compareTo(ts1);
        }
    };

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
        return slob.getURI();
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
            if (!s.file.exists()) {
                continue;
            }
            if (s.getURI().equals(slobURI)) {
                return s;
            }
        }
        return null;
    }


    public List<Slob> findAllSlobsByURI(String slobURI) {
        List<Slob> result = new ArrayList<Slob>();
        for (Slob s : slobs) {
            if (!s.file.exists()) {
                continue;
            }
            if (s.getURI().equals(slobURI)) {
                result.add(s);
            }
        }
        return result;
    }


    public Slob.Blob findRandom() {
        return findRandom(this.getSlobs());
    }

    public Slob.Blob findRandom(Slob[] slobs) {
        Set<String> types = new HashSet<String>(2);
        types.add("text/html");
        types.add("text/plain");
        return findRandom(types, slobs);
    }

    public Slob.Blob findRandom(Set<String> allowedContentTypes, Slob[] slobs) {
        if (slobs.length > 0) {
            for (int i = 0; i < 100; i++) {
                Slob slob = slobs[random.nextInt(slobs.length)];
                int size = slob.size();
                Slob.Blob blob = slob.get(random.nextInt(size));
                String contentType = blob.getContentType();
                ContentTypeParser ctParser = new ContentTypeParser(contentType);
                String parsedContentType = ctParser.getType();
                if (allowedContentTypes.contains(parsedContentType)) {
                    return blob;
                }
            }
        }
        return null;
    }

    private Map<String, Object> toInfoItem(Slob s) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("id", s.getId().toString());
        data.put("file", s.file.getAbsolutePath());
        data.put("compression", s.header.compression);
        data.put("encoding", s.header.encoding);
        data.put("blobCount", s.header.blobCount);
        data.put("refCount", s.size());
        data.put("contentTypes", s.header.contentTypes);
        data.put("tags", s.getTags());
        return data;
    }

    public Slobber() {

        json.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        json.configure(SerializationFeature.INDENT_OUTPUT, true);

        random = new Random();
        
        Properties sysProps = System.getProperties();

        Set<Entry<Object, Object>> propEntries = sysProps.entrySet();

        for (Entry <Object, Object> entry : propEntries) {
            String key = entry.getKey().toString();
            if (key.startsWith("slobber.static.")) {
                final String staticResValue = entry.getValue().toString();
                String[] parts = key.split("\\.", 3);
                final String staticMountPoint = parts[2];
                final String staticRes;
                if (new File(staticResValue).isDirectory() && !staticResValue.endsWith(File.separator)) {
                    staticRes = staticResValue + File.separator;
                }
                else {
                    staticRes = staticResValue;
                }
                L.info(String.format("Mounting %s at /%s", staticRes, staticMountPoint));
                handlers.put(staticMountPoint, new StaticContainer(staticRes));
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
                int limit = q.getInteger("limit") ;
                if (limit > 10000) {
                    response.setCode(413);
                    return;
                }
                if (limit <= 0) {
                    limit = 100;
                }
                Iterator<Slob.Blob> result = Slob.find(key, getSlobs());
                List<Map<String, String>> items = new ArrayList<Map<String, String>>();
                while (result.hasNext() && items.size() <= limit) {
                    Slob.Blob b = result.next();
                    Map<String, String> item = new HashMap<String, String>();
                    item.put("url", mkContentURL(b));
                    item.put("label", b.key);
                    item.put("dictLabel", b.owner.getTags().get("label"));
                    items.add(item);
                }
                response.setValue("Content-Type", "application/json");
                OutputStream out = response.getOutputStream();
                OutputStreamWriter os = new OutputStreamWriter(out, "UTF8");
                json.writeValue(os, items);
                os.close();
            }
        });

        handlers.put("random", new GETContainer() {
            @Override
            public void GET(Request request, Response response) throws Exception{
                Slob.Blob blob = findRandom();
                if (blob == null) {
                    notFound(response);
                    return;
                }
                Map<String, String> item = new HashMap<String, String>();
                item.put("url", mkContentURL(blob));
                item.put("label", blob.key);
                response.setValue("Content-Type", "application/json");
                response.setValue("Cache-Control", "no-cache");
                OutputStream out = response.getOutputStream();
                OutputStreamWriter os = new OutputStreamWriter(out, "UTF8");
                json.writeValue(os, item);
                os.close();
            }
        });

        handlers.put("slob", new GETContainer() {

            @Override
            protected void GET(Request req, Response resp)
                    throws Exception {
                /*

                /slob
                    (application/json) return list of slob info items

                /slob/{slob uuid}
                    (application/json) return slob info

                /slob/{slob uuid}/{key}
                /slob/{slob uuid}/{key}?blob={blob id}

                    (content-type) return content. Cache content with

                    Cache-Control: max-age=31556926

                    (approximately 1 year - practically forever, there may be trouble with larger values)

                /slob/{slob uri}/{key}

                    (content-type) return content. Maybe cache for some short period of time,
                    say 5 or 10 minutes, also set ETag to slob Id, so that subsequent requests
                    include If-None-Match. If slob uri resolves to a different slob id -
                    return new resource, otherwise Not Modified.

                 Need to make sure blobId is only ever used if slob id is specified,
                 never with slob uri

                 */

                String[] pathSegments = req.getPath().getSegments();
                if (pathSegments.length  == 1) {
                    resp.setValue("Content-Type", "application/json");
                    OutputStream out = resp.getOutputStream();
                    OutputStreamWriter os = new OutputStreamWriter(out, "UTF8");
                    Map data = new HashMap();
                    List infoItems = new ArrayList();
                    data.put("slobs", infoItems);
                    for (Slob s : slobs) {
                        infoItems.add(toInfoItem(s));
                    }
                    resp.setValue("Cache-Control", "no-cache");
                    json.writeValue(os, data);
                    return;
                }

                //FIXME URIs like "http://de.wikipedia.org"
                //(URL encoded: "http%3A%2F%2Fde.wikipedia.org") are not parsed correctly
                //and produce 3 segments. Looks like a bug in Simple

                if (pathSegments.length  == 2) {
                    resp.setValue("Content-Type", "application/json");
                    OutputStream out = resp.getOutputStream();
                    OutputStreamWriter os = new OutputStreamWriter(out, "UTF8");

                    String slobIdOrUri = pathSegments[1];
                    slobIdOrUri = URLDecoder.decode(slobIdOrUri, "UTF-8");
                    Slob s = findSlob(slobIdOrUri);

                    if (s == null) {
                        resp.setStatus(Status.NOT_FOUND);
                        json.writeValue(os, new HashMap<String, Object>());
                        return;
                    }

                    resp.setValue("Cache-Control", "no-cache");
                    json.writeValue(os, toInfoItem(s));
                    return;
                }

                Query q = req.getQuery();
                String ifNoneMatch = req.getValue("If-None-Match");
                String blobId = q.get("blob");

                String key = q.get("key");
                if (pathSegments.length >= 3) {
                    StringBuilder keyBuilder = new StringBuilder();
                    for (int i = 2; i < pathSegments.length; i++) {
                        String decodedSegment = URLDecoder.decode(pathSegments[i], "UTF-8");
                        keyBuilder.append(decodedSegment);
                        if (i < pathSegments.length - 1) {
                            keyBuilder.append('/');
                        }
                    }
                    key = keyBuilder.toString();
                }

                String slobIdOrUri = null;
                if (pathSegments.length >= 2) {
                    slobIdOrUri = pathSegments[1];
                    slobIdOrUri = URLDecoder.decode(slobIdOrUri, "UTF-8");
                }

                Slob slob = getSlob(slobIdOrUri);
                boolean isSlobId = true;
                if (slob == null) {
                    slob = findSlob(slobIdOrUri);
                    isSlobId = false;
                }

                if (slob == null) {
                    notFound(resp);
                    return;
                }

                if (isSlobId && blobId != null) {
                    resp.setValue("Cache-Control", "max-age=31556926");
                    Slob.Content reader = slob.getContent(blobId);
                    serveContent(resp, reader);
                    return;
                }

                if (key != null && ifNoneMatch != null) {
                    if (mkETag(slob.getId()).equals(ifNoneMatch)) {
                        resp.setStatus(Status.NOT_MODIFIED);
                        return;
                    }
                }


                List<Slob> candidates = findAllSlobsByURI(slob.getURI());

                Collections.sort(candidates, createTimeComparator);

                Iterator<Slob.Blob> result = Slob.find(key,
                        candidates.toArray(new Slob[candidates.size()]),
                        slob, Slob.Strength.SECONDARY);
                if (result.hasNext()) {
                    Slob.Blob blob = result.next();
                    if (isSlobId) {
                        resp.setValue("Cache-Control", "max-age=31556926");
                    }
                    else {
                        resp.setValue("Cache-Control", "max-age=600");
                        resp.setValue("ETag", mkETag(slob.getId()));
                    }
                    serveContent(resp, blob.getContent());
                    return;
                }

                notFound(resp);
            }
        });

        handlers.put("res", new ResourceContainer());
    }

    private void serveContent(Response resp,
                              Slob.Content content) throws IOException {
        resp.setValue("Content-Type", content.type);
        ByteBuffer bytes = content.data;
        resp.getByteChannel().write(bytes);
    }

    public Server start(String addrStr, int port) throws IOException {
        Server server = new ContainerServer(this, 16);
        Connection connection = new SocketConnection(server);
        SocketAddress address = new InetSocketAddress(InetAddress.getByName(addrStr), port);
        connection.connect(address);
        return server;
    }

    static void notFound(Response resp) throws IOException {
        resp.setStatus(Status.NOT_FOUND);
        resp.setValue("Content-Type", "text/plain");
        PrintStream body = resp.getPrintStream();
        body.printf("Not found");
    }

    public static String mkContentURL(Slob.Blob b) {
        return String.format("/slob/%s/%s?blob=%s#%s",
                    b.owner.getId(),
                    EncodingUtil.encodeURIComponent(b.key),
                    b.id, b.fragment);
    }

    private String mkETag(UUID slobId) {
        return String.format("\"%s\"", slobId);
    }

    public void handle(Request req, Response resp) {
        if (L.isLoggable(Level.FINE)) {
            L.fine(req.toString());
        }
        String[] pathSegments = req.getPath().getSegments();
        String resourceName;
        if (pathSegments.length == 0) {
            resourceName = "";
        }
        else {
            resourceName = pathSegments[0];
        }
        L.fine("Looking for handler for '" + resourceName + "'");
        Container handler = this.handlers.get(resourceName);
        if (handler == null) {
            defaultResourceContainer.handle(req, resp);
        }
        else {
            handler.handle(req, resp);
        }
        if (L.isLoggable(Level.INFO)) {
            StringBuilder s = new StringBuilder();
            s.append(req.getMethod());
            s.append(" ");
            s.append(req.getPath());
            String qs = req.getQuery().toString();
            if (qs.length() > 0) {
                s.append("?");
                s.append(qs);
            }
            s.append(" ");
            s.append(resp.getCode());
            L.info(s.toString());
        }
    }

    public static class LogFormatter extends Formatter {

        private DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            StringBuilder s = new StringBuilder();
            Date dt = new Date(record.getMillis());
            s.append(df.format(dt));
            s.append(" ");
            s.append(record.getMessage());
            s.append('\n');
            return s.toString();
        }
    }


    /**
     * Utility class for JavaScript compatible UTF-8 encoding and decoding.
     *
     * @see http://stackoverflow.com/questions/607176/java-equivalent-to-javascripts-encodeuricomponent-that-produces-identical-output
     * @author John Topley
     */
    static class EncodingUtil
    {
        /**
         * Decodes the passed UTF-8 String using an algorithm that's compatible with
         * JavaScript's <code>decodeURIComponent</code> function. Returns
         * <code>null</code> if the String is <code>null</code>.
         *
         * @param s The UTF-8 encoded String to be decoded
         * @return the decoded String
         */
        static String decodeURIComponent(String s)
        {
            if (s == null)
            {
                return null;
            }

            String result = null;

            try
            {
                result = URLDecoder.decode(s, "UTF-8");
            }

            // This exception should never occur.
            catch (UnsupportedEncodingException e)
            {
                result = s;
            }

            return result;
        }

        /**
         * Encodes the passed String as UTF-8 using an algorithm that's compatible
         * with JavaScript's <code>encodeURIComponent</code> function. Returns
         * <code>null</code> if the String is <code>null</code>.
         *
         * @param s The String to be encoded
         * @return the encoded String
         */
        static String encodeURIComponent(String s)
        {
            String result = null;

            try
            {
                result = URLEncoder.encode(s, "UTF-8")
                        .replaceAll("\\+", "%20")
                        .replaceAll("\\%21", "!")
                        .replaceAll("\\%27", "'")
                        .replaceAll("\\%28", "(")
                        .replaceAll("\\%29", ")")
                        .replaceAll("\\%7E", "~");
            }
            // This exception should never occur.
            catch (UnsupportedEncodingException e)
            {
                result = s;
            }

            return result;
        }
    }

    static FilenameFilter slobNameFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".slob");
        }
    };

    static void add(List<Slob> slobs, File file) throws IOException {
        if (file.isDirectory()) {
            for (String name : file.list(slobNameFilter)) {
                add(slobs, new File(file, name));
            }
        }
        else {
            slobs.add(new Slob(file));
        }
    }

    public static void main(String[] args) throws Exception {
        LogFormatter formatter = new LogFormatter();
        for (Handler h : Logger.getLogger("").getHandlers()) {
            h.setFormatter(formatter);
        }
        List<Slob> slobs = new ArrayList<Slob>();
        for (int i = 0; i < args.length; i++) {
            File f = new File(args[i]);
            add(slobs, f);
        }
        int port = Integer.parseInt(System.getProperty("slobber.port", "8013"));
        String addr = System.getProperty("slobber.host", "127.0.0.1");
        String url = String.format("http://%s:%s", addr, port);
        Slobber slobber = new Slobber();
        slobber.setSlobs(slobs);
        slobber.start(addr, port);
        System.out.println("Listening at " + url);

        boolean browse = Boolean.getBoolean("slobber.browse");
        if (browse) {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
            else {
                L.warning("Desktop not supported, can't open browser");
            }
        }
    }
 }