import java.util.HashMap;

/**
 * Created by Family on 8/4/16.
 */
public class GraphNode {
    private double id;
    private double lat;
    private double lon;
    private HashMap<Double, GraphNode> neighbors = new HashMap<>();

    public GraphNode(double id, double lat, double lon, HashMap neighbors) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        this.neighbors = neighbors;
    }

    public double getid() {
        return id;
    }
    public double getlat() {
        return lat;
    }
    public double getlon() {
        return lon;
    }
    public HashMap<Double, GraphNode> getneighbors() {
        return neighbors;
    }


}
