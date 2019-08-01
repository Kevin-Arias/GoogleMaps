import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.List;

/* Maven is used to pull in these dependencies. */
import com.google.gson.Gson;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;


import static spark.Spark.*;

/**
 * This MapServer class is the entry point for running the JavaSpark web server for the BearMaps
 * application project, receiving API calls, handling the API call processing, and generating
 * requested images and routes.
 * @author Alan Yao
 */
public class MapServer {
    /**
     * The root upper left/lower right longitudes and latitudes represent the bounding box of
     * the root tile, as the images in the img/ folder are scraped.
     * Longitude == x-axis; latitude == y-axis.
     */
    public static final double ROOT_ULLAT = 37.892195547244356, ROOT_ULLON = -122.2998046875,
            ROOT_LRLAT = 37.82280243352756, ROOT_LRLON = -122.2119140625;
    /** Each tile is 256x256 pixels. */
    public static final int TILE_SIZE = 256;
    /** HTTP failed response. */
    private static final int HALT_RESPONSE = 403;
    /** Route stroke information: typically roads are not more than 5px wide. */
    public static final float ROUTE_STROKE_WIDTH_PX = 5.0f;
    /** Route stroke information: Cyan with half transparency. */
    public static final Color ROUTE_STROKE_COLOR = new Color(108, 181, 230, 200);
    /** The tile images are in the IMG_ROOT folder. */
    private static final String IMG_ROOT = "img/";
    /**
     * The OSM XML file path. Downloaded from <a href="http://download.bbbike.org/osm/">here</a>
     * using custom region selection.
     **/
    private static final String OSM_DB_PATH = "berkeley.osm";
    /**
     * Each raster request to the server will have the following parameters
     * as keys in the params map accessible by,
     * i.e., params.get("ullat") inside getMapRaster(). <br>
     * ullat -> upper left corner latitude,<br> ullon -> upper left corner longitude, <br>
     * lrlat -> lower right corner latitude,<br> lrlon -> lower right corner longitude <br>
     * w -> user viewport window width in pixels,<br> h -> user viewport height in pixels.
     **/
    private static final String[] REQUIRED_RASTER_REQUEST_PARAMS = {"ullat", "ullon", "lrlat",
        "lrlon", "w", "h"};
    /**
     * Each route request to the server will have the following parameters
     * as keys in the params map.<br>
     * start_lat -> start point latitude,<br> start_lon -> start point longitude,<br>
     * end_lat -> end point latitude, <br>end_lon -> end point longitude.
     **/
    private static final String[] REQUIRED_ROUTE_REQUEST_PARAMS = {"start_lat", "start_lon",
        "end_lat", "end_lon"};
    /* Define any static variables here. Do not define any instance variables of MapServer. */
    private static GraphDB g;
    private static QuadTree newtree;
    private static HashMap<String, BufferedImage> rememberer;
    private static HashMap<Map<String, Double>, List<Long>> memoize;

    /**
     * Place any initialization statements that will be run before the server main loop here.
     * Do not place it in the main function. Do not place initialization code anywhere else.
     * This is for testing purposes, and you may fail tests otherwise.
     **/
    public static void initialize() {
        g = new GraphDB(OSM_DB_PATH);
        newtree = new QuadTree(ROOT_ULLON, ROOT_ULLAT, ROOT_LRLON, ROOT_LRLAT, 0, 0);
        rememberer = new HashMap<>();
        memoize = new HashMap<>();
    }

    public static void main(String[] args) {
        initialize();
        staticFileLocation("/page");
        /* Allow for all origin requests (since this is not an authenticated server, we do not
         * care about CSRF).  */
        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Request-Method", "*");
            response.header("Access-Control-Allow-Headers", "*");
        });

        /* Define the raster endpoint for HTTP GET requests. I use anonymous functions to define
         * the request handlers. */
        get("/raster", (req, res) -> {
            HashMap<String, Double> rasterParams =
                    getRequestParams(req, REQUIRED_RASTER_REQUEST_PARAMS);
            /* Required to have valid raster params */
            validateRequestParameters(rasterParams, REQUIRED_RASTER_REQUEST_PARAMS);
            /* Create the Map for return parameters. */
            Map<String, Object> rasteredImgParams = new HashMap<>();
            /* getMapRaster() does almost all the work for this API call */
            BufferedImage im = getMapRaster(rasterParams, rasteredImgParams);
            /* Check if we have routing parameters. */
            HashMap<String, Double> routeParams =
                    getRequestParams(req, REQUIRED_ROUTE_REQUEST_PARAMS);
            /* If we do, draw the route too. */
            if (hasRequestParameters(routeParams, REQUIRED_ROUTE_REQUEST_PARAMS)) {
                findAndDrawRoute(routeParams, rasteredImgParams, im);
            }
            /* On an image query success, add the image data to the response */
            if (rasteredImgParams.containsKey("query_success")
                    && (Boolean) rasteredImgParams.get("query_success")) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                writeJpgToStream(im, os);
                String encodedImage = Base64.getEncoder().encodeToString(os.toByteArray());
                rasteredImgParams.put("b64_encoded_image_data", encodedImage);
                os.flush();
                os.close();
            }
            /* Encode response to Json */
            Gson gson = new Gson();
            return gson.toJson(rasteredImgParams);
        });

        /* Define the API endpoint for search */
        get("/search", (req, res) -> {
            Set<String> reqParams = req.queryParams();
            String term = req.queryParams("term");
            Gson gson = new Gson();
            /* Search for actual location data. */
            if (reqParams.contains("full")) {
                List<Map<String, Object>> data = getLocations(term);
                return gson.toJson(data);
            } else {
                /* Search for prefix matching strings. */
                List<String> matches = getLocationsByPrefix(term);
                return gson.toJson(matches);
            }
        });

        /* Define map application redirect */
        get("/", (request, response) -> {
            response.redirect("/map.html", 301);
            return true;
        });
    }

    /**
     * Check if the computed parameter map matches the required parameters on length.
     */
    private static boolean hasRequestParameters(
            HashMap<String, Double> params, String[] requiredParams) {
        return params.size() == requiredParams.length;
    }

    /**
     * Validate that the computed parameters matches the required parameters.
     * If the parameters do not match, halt.
     */
    private static void validateRequestParameters(
            HashMap<String, Double> params, String[] requiredParams) {
        if (params.size() != requiredParams.length) {
            halt(HALT_RESPONSE, "Request failed - parameters missing.");
        }
    }

    /**
     * Return a parameter map of the required request parameters.
     * Requires that all input parameters are doubles.
     * @param req HTTP Request
     * @param requiredParams TestParams to validate
     * @return A populated map of input parameter to it's numerical value.
     */
    private static HashMap<String, Double> getRequestParams(
            spark.Request req, String[] requiredParams) {
        Set<String> reqParams = req.queryParams();
        HashMap<String, Double> params = new HashMap<>();
        for (String param : requiredParams) {
            if (reqParams.contains(param)) {
                try {
                    params.put(param, Double.parseDouble(req.queryParams(param)));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                    halt(HALT_RESPONSE, "Incorrect parameters - provide numbers.");
                }
            }
        }
        return params;
    }

    /**
     * Write a <code>BufferedImage</code> to an <code>OutputStream</code>. The image is written as
     * a lossy JPG, but with the highest quality possible.
     * @param im Image to be written.
     * @param os Stream to be written to.
     */
    static void writeJpgToStream(BufferedImage im, OutputStream os) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(1.0F); // Highest quality of jpg possible
        writer.setOutput(new MemoryCacheImageOutputStream(os));
        try {
            writer.write(im);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles raster API calls, queries for tiles and rasters the full image. <br>
     * <p>
     *     The rastered photo must have the following properties:
     *     <ul>
     *         <li>Has dimensions of at least w by h, where w and h are the user viewport width
     *         and height.</li>
     *         <li>The tiles collected must cover the most longitudinal distance per pixel
     *         possible, while still covering less than or equal to the amount of
     *         longitudinal distance per pixel in the query box for the user viewport size. </li>
     *         <li>Contains all tiles that intersect the query bounding box that fulfill the
     *         above condition.</li>
     *         <li>The tiles must be arranged in-order to reconstruct the full image.</li>
     *     </ul>
     *     Additional image about the raster is returned and is to be included in the Json response.
     * </p>
     * @param inputParams Map of the HTTP GET request's query parameters - the query bounding box
     *                    and the user viewport width and height.
     * @param rasteredImageParams A map of parameters for the Json response as specified:
     * "raster_ul_lon" -> Double, the bounding upper left longitude of the rastered image <br>
     * "raster_ul_lat" -> Double, the bounding upper left latitude of the rastered image <br>
     * "raster_lr_lon" -> Double, the bounding lower right longitude of the rastered image <br>
     * "raster_lr_lat" -> Double, the bounding lower right latitude of the rastered image <br>
     * "raster_width"  -> Integer, the width of the rastered image <br>
     * "raster_height" -> Integer, the height of the rastered image <br>
     * "depth"         -> Integer, the 1-indexed quadtree depth of the nodes of the rastered image.
     * Can also be interpreted as the length of the numbers in the image string. <br>
     * "query_success" -> Boolean, whether an image was successfully rastered. <br>
     * @return a <code>BufferedImage</code>, which is the rastered result.
     * @see #REQUIRED_RASTER_REQUEST_PARAMS
     */

    public static BufferedImage getMapRaster(Map<String, Double> inputParams,
                                             Map<String, Object> rasteredImageParams) {

        double ullon = inputParams.get("ullon");
        double ullat = inputParams.get("ullat");
        double lrlon = inputParams.get("lrlon");
        double lrlat = inputParams.get("lrlat");
        double w = inputParams.get("w");
        double h = inputParams.get("h");
        List<QuadTree> listofsuccessfultrees = new ArrayList<>();
        newtree.rasteredImages(listofsuccessfultrees, newtree,
                (inputParams.get("lrlon") - inputParams.get("ullon")) / inputParams.get("w"),
                inputParams);
        Collections.sort(listofsuccessfultrees);
        List<String> imagenames = newtree.converttoString(listofsuccessfultrees);
        List<Integer> widthandheight = newtree.findwidthheight(listofsuccessfultrees);
        BufferedImage result = new BufferedImage(widthandheight.get(1) * 256,
                widthandheight.get(0) * 256, BufferedImage.TYPE_3BYTE_BGR);
        Graphics graph = result.getGraphics();
        rasteredImageParams.put("raster_ul_lon", listofsuccessfultrees.get(0).returnULLON());
        rasteredImageParams.put("raster_ul_lat", listofsuccessfultrees.get(0).returnULLAT());
        rasteredImageParams.put("raster_lr_lon",
                listofsuccessfultrees.get(listofsuccessfultrees.size() - 1).returnLRLON());
        rasteredImageParams.put("raster_lr_lat",
                listofsuccessfultrees.get(listofsuccessfultrees.size() - 1).returnLRLAT());
        rasteredImageParams.put("raster_width", widthandheight.get(1) * 256);
        rasteredImageParams.put("raster_height", widthandheight.get(0) * 256);
        rasteredImageParams.put("depth", imagenames.get(0).length());
        rasteredImageParams.put("query_success", true);
        try {
            int x = 0;
            int y = 0;
            for (String image : imagenames) {
                BufferedImage im = null;
                if (rememberer.containsKey(image)) {
                    im = rememberer.get(image);
                } else {
                    im = ImageIO.read(new File(IMG_ROOT + image + ".png"));
                    rememberer.put(image, im);
                }
                graph.drawImage(im, x, y, null);
                x += 256;
                if (x >= result.getWidth()) {
                    x = 0;
                    y += 256;
                }
            }
        } catch (IOException ioException) {
            System.out.println("Could not read image");
        }
        return result;
    }
    public static double euclidean(double lon, double goallon, double lat, double goallat) {
        double result = Math.sqrt(Math.pow(Math.abs(lon - goallon), 2)
                + Math.abs(Math.pow(Math.abs(lat - goallat), 2)));
        return result;
    }


    /**
     * Searches for the shortest route satisfying the input request parameters, and returns a
     * <code>List</code> of the route's node ids. <br>
     * The route should start from the closest node to the start point and end at the closest node
     * to the endpoint. Distance is defined as the euclidean distance between two points
     * (lon1, lat1) and (lon2, lat2).
     * If <code>im</code> is not null, draw the route onto the image by drawing lines in between
     * adjacent points in the route. The lines should be drawn using ROUTE_STROKE_COLOR,
     * ROUTE_STROKE_WIDTH_PX, BasicStroke.CAP_ROUND and BasicStroke.JOIN_ROUND.
     * @param routeParams Params collected from the API call. Members are as
     *                    described in REQUIRED_ROUTE_REQUEST_PARAMS.
     * @param rasterImageParams parameters returned from the image rastering.
     * @param im The rastered map image to be drawn on.
     * @return A List of node ids from the start of the route to the end.
     */

    public static List<Long> findAndDrawRoute(Map<String, Double> routeParams,
                                              Map<String, Object> rasterImageParams,
                                              BufferedImage im) {
        if (memoize.containsKey(routeParams) && rasterImageParams != null) {
            ArrayList<GraphNode> ogpath = new ArrayList<>();
            for (int x = 0; x < memoize.get(routeParams).size(); x++) {
                GraphNode adder = g.getresult().get((double) memoize.get(routeParams).get(x));
                ogpath.add(adder);
            }
            drawme(rasterImageParams, im, ogpath);
            return memoize.get(routeParams);
        }
        HashMap<GraphNode, Double> distance = new HashMap<>();
        HashMap<GraphNode, GraphNode> previous = new HashMap<>();
        HashMap temp = new HashMap<Double, GraphNode>(g.getresult());
        double closesttostart = Double.MAX_VALUE;
        double closesttoend = Double.MAX_VALUE;
        double starterid = 0;
        double enderid = 0;
        for (Object value : temp.values()) {
            GraphNode user = (GraphNode) value;
            double startherustic = Math.sqrt(
                    Math.pow(Math.abs(
                            user.getlon() - routeParams.get("start_lon")), 2)
                    + Math.abs(Math.pow(Math.abs(
                            user.getlat() - routeParams.get("start_lat")), 2)));
            double endherustic = Math.sqrt(
                    Math.pow(Math.abs(user.getlon() - routeParams.get("end_lon")), 2)
                    + Math.abs(Math.pow(
                            Math.abs(user.getlat() - routeParams.get("end_lat")), 2)));
            if (startherustic < closesttostart) {
                closesttostart = startherustic; starterid = user.getid();
            }
            if (endherustic < closesttoend) {
                closesttoend = endherustic; enderid = user.getid();
            }
        }
        class NodeComparator implements Comparator {
            public int compare(Object o1, Object o2) {
                GraphNode node1 = (GraphNode) o1;
                GraphNode node2 = (GraphNode) o2;
                if (distance.get(o1) > distance.get(o2)) {
                    return 1;
                } else if (distance.get(o1) < distance.get(o2)) {
                    return -1;
                } else {
                    return 0;
                }
            }
        }
        Queue<GraphNode> fringe = new PriorityQueue<GraphNode>(new NodeComparator());
        distance.put(g.getresult().get(starterid), 0.0);
        fringe.add(g.getresult().get(starterid));
        while (!fringe.isEmpty()) {
            GraphNode current = fringe.poll();
            if (current.getid() == enderid) {
                break;
            }
            for (Object value : current.getneighbors().values()) {
                GraphNode user = (GraphNode) value;
                if (!distance.containsKey(user)) {
                    distance.put(user, Double.MAX_VALUE);
                }
                double curr2user = euclidean(current.getlon(), user.getlon(),
                        current.getlat(), user.getlat());
                if (distance.get(user) > distance.get(current)
                        + curr2user) {
                    distance.put(user, (distance.get(current)
                            + curr2user));
                    previous.put(user, current);
                    fringe.add(user);
                }

            }
        }
        ArrayList<Long> finalresult = new ArrayList<>();
        ArrayList<GraphNode> path = new ArrayList<>();
        GraphNode i = g.getresult().get(enderid);
        finalresult.add((long) i.getid());
        path.add(i);
        while (!i.equals(g.getresult().get(starterid))) {
            finalresult.add((long) previous.get(i).getid());
            path.add(previous.get(i));
            i = previous.get(i);
        }
        Collections.reverse(finalresult);
        Collections.reverse(path);
        if (rasterImageParams != null) {
            drawme(rasterImageParams, im, path);
        }
        memoize.put(routeParams, finalresult);
        return finalresult;
    }

    public static void drawme(Map<String, Object> rasterParams, BufferedImage tempim,
                              ArrayList<GraphNode> route) {
        double ullon = (Double) rasterParams.get("raster_ul_lon");
        double ullat = (Double) rasterParams.get("raster_ul_lat");
        double lrlon = (Double) rasterParams.get("raster_lr_lon");
        double lrlat = (Double) rasterParams.get("raster_lr_lat");
        int width = (Integer) rasterParams.get("raster_width");
        int height = (Integer) rasterParams.get("raster_height");
        double x = Math.abs(ullon - lrlon) / width;
        double y = Math.abs(ullat - lrlat) / height;
        Graphics2D newImage = (Graphics2D) tempim.getGraphics();
        BasicStroke stroke = new BasicStroke(MapServer.ROUTE_STROKE_WIDTH_PX,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        newImage.setStroke(stroke);
        newImage.setColor(ROUTE_STROKE_COLOR);
        for (int n = 0; n < route.size() - 1; n++) {
            double x1 = (route.get(n).getlon() - ullon) / x;
            double y1 = (ullat - route.get(n).getlat()) / y;
            double x2 = (route.get(n + 1).getlon() - ullon) / x;
            double y2 = (ullat - route.get(n + 1).getlat()) / y;
            newImage.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
        }
    }

    /**
     * In linear time, collect all the names of OSM locations that prefix-match the query string.
     * @param prefix Prefix string to be searched for. Could be any case, with our without
     *               punctuation.
     * @return A <code>List</code> of the full names of locations whose cleaned name matches the
     * cleaned <code>prefix</code>.
     */
    public static List<String> getLocationsByPrefix(String prefix) {
        return new LinkedList<>();
    }

    /**
     * Collect all locations that match a cleaned <code>locationName</code>, and return
     * information about each node that matches.
     * @param locationName A full name of a location searched for.
     * @return A list of locations whose cleaned name matches the
     * cleaned <code>locationName</code>, and each location is a map of parameters for the Json
     * response as specified: <br>
     * "lat" -> Number, The latitude of the node. <br>
     * "lon" -> Number, The longitude of the node. <br>
     * "name" -> String, The actual name of the node. <br>
     * "id" -> Number, The id of the node. <br>
     */
    public static List<Map<String, Object>> getLocations(String locationName) {
        return new LinkedList<>();
    }
}
