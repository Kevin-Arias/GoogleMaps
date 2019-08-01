import java.util.*;

/**
 * Created by Family on 7/17/16.
 */
public class QuadTree implements Comparable<QuadTree> {
    private QuadTree parent;
    private QuadTree[] children;
    private double ullon;
    private double ullat;
    private double lrlon;
    private double lrlat;
    private int img;
    private double depth;

    public QuadTree(double ullon, double ullat, double lrlon, double lrlat, int img, double depth) {
        this.ullon = ullon;
        this.ullat = ullat;
        this.lrlon = lrlon;
        this.lrlat = lrlat;
        this.img = img;
        this.depth = depth;
        this.children = new QuadTree[4];
        if (this.depth < 7) {
            this.children[0] = new QuadTree(ullon, ullat, (ullon + lrlon) / 2,
                    (ullat + lrlat) / 2, (img * 10) + 1, depth + 1);
            this.children[1] = new QuadTree((ullon + lrlon) / 2, ullat, lrlon,
                    (ullat + lrlat) / 2, (img * 10) + 2, depth + 1);
            this.children[2] = new QuadTree(ullon, (ullat + lrlat) / 2,
                    (ullon + lrlon) / 2, lrlat, (img * 10) + 3, depth + 1);
            this.children[3] = new QuadTree((ullon + lrlon) / 2,
                    (ullat + lrlat) / 2, lrlon, lrlat, (img * 10) + 4, depth + 1);
        } else {
            this.children = null;
        }
    }
    public void rasteredImages(List lisstrees, QuadTree tree,
                               double querydpp, Map<String, Double> params) {
        if (intersectsTile(tree.children[0], params)) {
            if (satisfiesDepthorisLeaf(tree.children[0], 256, querydpp)) {

                lisstrees.add(tree.children[0]);
            } else {
                rasteredImages(lisstrees, tree.children[0], querydpp, params);
            }
        }
        if (intersectsTile(tree.children[1], params)) {
            if (satisfiesDepthorisLeaf(tree.children[1], 256, querydpp)) {

                lisstrees.add(tree.children[1]);
            } else {
                rasteredImages(lisstrees, tree.children[1], querydpp, params);
            }
        }
        if (intersectsTile(tree.children[2], params)) {
            if (satisfiesDepthorisLeaf(tree.children[2], 256, querydpp)) {

                lisstrees.add(tree.children[2]);
            } else {
                rasteredImages(lisstrees, tree.children[2], querydpp, params);
            }
        }
        if (intersectsTile(tree.children[3], params)) {
            if (satisfiesDepthorisLeaf(tree.children[3], 256, querydpp)) {
                lisstrees.add(tree.children[3]);
            } else {
                rasteredImages(lisstrees, tree.children[3], querydpp, params);
            }
        }
    }
    public boolean intersectsTile(QuadTree tree, Map<String, Double> params) {
        return ((tree.ullon <= params.get("lrlon"))
                && (params.get("ullon") <= tree.lrlon)
                && (tree.ullat >= params.get("lrlat"))
                && (params.get("ullat") >= tree.lrlat));
    }
    public boolean satisfiesDepthorisLeaf(QuadTree tree, double width, double querydpp) {
        if (((tree.lrlon - tree.ullon) / width) <= querydpp || tree.children == null) {
            return true;
        }
        return false;
    }
    public int compareTo(QuadTree compared) {
        if (this.ullat == compared.ullat) {
            if (this.ullon < compared.ullon) {
                return -1;
            } else {
                return 1;
            }
        } else if (this.ullat < compared.ullat) {
            return 1;
        } else if (this.ullat > compared.ullat) {
            return -1;
        } else {
            return 0;
        }
    }

    public List<String> converttoString(List<QuadTree> list) {
        List<String> files = new ArrayList<>();
        for (QuadTree tree : list) {
            files.add(Integer.toString(tree.img));
        }
        return files;
    }

    public List<Integer> findwidthheight(List<QuadTree> findCoordinates) {
        HashSet<Double> lat = new HashSet<>();
        HashSet<Double> lon = new HashSet<>();
        List<Integer> widthandheight = new ArrayList<>();
        QuadTree first = findCoordinates.get(0);
        for (QuadTree tree : findCoordinates) {
            lat.add(tree.ullat);
            lon.add(tree.ullon);
        }
        widthandheight.add(lat.size());
        widthandheight.add(lon.size());

        return widthandheight;

    }

    public double returnULLON() {
        return this.ullon;
    }
    public double returnULLAT() {
        return this.ullat;
    }
    public double returnLRLON() {
        return this.lrlon;
    }
    public double returnLRLAT() {
        return this.lrlat;
    }

}
