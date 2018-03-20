package general;

import adapters.GMapsPlannerAdapter;
import adapters.OpenTripPlannerAdapter;
import adapters.PlannerAdapter;
import com.umotional.basestructures.Graph;
import com.umotional.basestructures.Node;
import model.graph.GraphEdge;
import model.planner.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import pathfinding.AStar;
import pathfinding.kdtree.KDTree;

import java.util.ArrayList;
import java.util.List;

public class RoutePlanner {
    private PlannerAdapter[] plannerAdapters;

    private static final short NUM_OF_PATHS = 40;
    private static final Logger logger = LogManager.getLogger(RoutePlanner.class);

    RoutePlanner() {
        // add more planner adapters if they exist
        plannerAdapters = new PlannerAdapter[]{new GMapsPlannerAdapter(), new OpenTripPlannerAdapter()};
    }

    public List<Route> expandGraph() {
        Location[] locArray;
        List<Route> routes = new ArrayList<>();
        List<Route> routeList;

        for (PlannerAdapter plannerAdapter : plannerAdapters) {
            // Uncomment for loop for generating more routes
            for (int i = 0; i < NUM_OF_PATHS; i++) {
                locArray = Location.generateRandomLocationsInPrague(2);
                routeList = plannerAdapter.findRoutes(locArray[0], locArray[1]);
                routes.addAll(routeList);
                routeList = plannerAdapter.findRoutes(locArray[1], locArray[0]);
                routes.addAll(routeList);
            }
        }

        return routes;
    }

    public List<GraphEdge> findRandomPath() {
        Location[] locArray = Location.generateRandomLocationsInPrague(2);

        return findPath(locArray[0], locArray[1]);
    }

    private Route findRoute(int fromId, int toId, TransportMode mode) {
        Graph graph = GraphMaker.getInstance().getGraph();

        Node from = graph.getNode(fromId);
        Node to = graph.getNode(toId);

        Route route = null;

        Location locFrom = new Location(from.getLatitude(), from.getLongitude());
        Location locTo = new Location(to.getLatitude(), to.getLongitude());
        if (mode == TransportMode.TRANSIT) {
            route = new OpenTripPlannerAdapter().findRoute(locFrom, locTo, TransportMode.TRANSIT);
        } else if (mode == TransportMode.CAR || mode == TransportMode.WALK) {
            route = new GMapsPlannerAdapter().findRoute(locFrom, locTo, mode);
        }

        return route;
    }

    public List<Location> getLocationSequence(Route route) {
        List<Location> locationList = new ArrayList<>();
        locationList.add(route.origin);

        for (Leg leg : route.legList) {
            for (Step step : leg.steps) {
                locationList.add(step.startLocation);
                locationList.add(step.endLocation);
            }

        }
        locationList.add(route.destination);
        return locationList;
    }


    public List<Location> getLocationsFromEdges(List<GraphEdge> edgeList) {
        if (edgeList.isEmpty()) return new ArrayList<>();

        Graph graph = GraphMaker.getInstance().getGraph();
        List<Location> locationList = new ArrayList<>();
        Node nodeFrom;
        for (GraphEdge edge : edgeList) {
            nodeFrom = graph.getNode(edge.fromId);
            locationList.add(new Location(nodeFrom.getLatitude(), nodeFrom.getLongitude()));
        }
        Node nodeTo = graph.getNode(edgeList.get(edgeList.size() - 1).toId);
        locationList.add(new Location(nodeTo.getLatitude(), nodeTo.getLongitude()));

        return locationList;
    }


    public Route doRefinement(List<GraphEdge> plan) {
        if (plan.isEmpty()) return null;

        GraphEdge startEdge = plan.get(0);
        int fromId = startEdge.fromId;
        int toId = startEdge.toId;
        GraphEdge curEdge;
        Graph graph = GraphMaker.getInstance().getGraph();

        Route route = new Route();
        route.origin = Location.getLocation(graph.getNode(fromId));
        route.destination = Location.getLocation(graph.getNode(plan.get(plan.size() - 1).toId));

        for (int i = 1; i < plan.size(); i++) {
            curEdge = plan.get(i);
            if (startEdge.mode != curEdge.mode) {
                Route tmpRoute = findRoute(fromId, toId, startEdge.mode);
                route.legList.addAll(tmpRoute.legList);
                startEdge = curEdge;
                fromId = startEdge.fromId;
                toId = startEdge.toId;
            } else {
                toId = curEdge.toId;
            }
        }
        Route tmpRoute = findRoute(fromId, toId, startEdge.mode);
        route.legList.addAll(tmpRoute.legList);

        return route;
    }

    public void getPathDescription(Route route, String name) {
        if (route.legList == null || route.legList.isEmpty()) {
            logger.error("Path is empty");
            return;
        }

        long routeDuration = route.legList.stream().mapToLong(o -> o.durationInSeconds).sum();
        logger.info("Duration of " + name + " path: " + routeDuration + " seconds");
        logger.info("Number of transfers: " + route.legList.size());
    }

    public void getPathDescription(List<GraphEdge> path, String name) {
        if (path == null || path.isEmpty()) {
            logger.error("Path is empty");
            return;
        }

        int numOfTransfers = 0;

        GraphEdge curEdge;
        GraphEdge prevEdge;
        for (int i = 1; i < path.size(); i++) {
            prevEdge = path.get(i - 1);
            curEdge = path.get(i);
            numOfTransfers += (prevEdge.mode == curEdge.mode) ? 0 : 1;
        }

        long routeDuration = path.stream().mapToLong(o -> o.durationInSeconds).sum();
        logger.info("Duration of " + name + " path: " + routeDuration + " seconds");
        logger.info("Number of transfers: " + numOfTransfers);

    }

    public void createKDTree() {
        Graph<Node, GraphEdge> graph = GraphMaker.getInstance().getGraph();
        logger.info("Creating KDTree...");
        double[] tmpArr = new double[2];
        for (Node node : graph.getAllNodes()) {
            tmpArr[0] = node.getLatitude();
            tmpArr[1] = node.getLongitude();
            KDTree.getInstance().insert(tmpArr, node.id);
        }
        logger.info("KDTree created");
    }

    public List<GraphEdge> findPath(Location origin, Location destination) {
        Graph graph = GraphMaker.getInstance().getGraph();

        int originId = (int) KDTree.getInstance().nearest(origin.toDoubleArray());
        int destinationId = (int) KDTree.getInstance().nearest(destination.toDoubleArray());

        Node originNode = graph.getNode(originId);
        Node destinationNode = graph.getNode(destinationId);

        AStar astar = new AStar<>(graph);
        List<GraphEdge> plan = astar.plan(originNode, destinationNode);

        if (plan == null) {
            logger.debug("Plan was empty, trying another combination now...");
            return findRandomPath();
        }

//        logger.info("Generated random origin location" + origin);
//        logger.info("Generated random destination location" + destination);
//        logger.info("Founded node as origin location" + Location.getLocation(originNode));
//        logger.info("Founded node as destination location" + Location.getLocation(destinationNode));

        return plan;
    }
}
