package cz.cvut.fel.intermodal_planning.graph;

import com.umotional.basestructures.Graph;
import com.umotional.basestructures.GraphBuilder;
import com.umotional.basestructures.Node;
import cz.cvut.fel.intermodal_planning.planner.model.*;
import cz.cvut.fel.intermodal_planning.subplanners.adapters.GMapsPlannerAdapter;
import cz.cvut.fel.intermodal_planning.subplanners.adapters.OTPlannerAdapter;
import cz.cvut.fel.intermodal_planning.subplanners.adapters.PlannerAdapter;
import cz.cvut.fel.intermodal_planning.general.Storage;
import cz.cvut.fel.intermodal_planning.graph.enums.GraphExpansionStrategy;
import cz.cvut.fel.intermodal_planning.graph.model.GraphEdge;
import cz.cvut.fel.intermodal_planning.planner.pathfinding.kdtree.KDTree;
import cz.cvut.fel.intermodal_planning.general.utils.LocationUtils;
import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by Ondrej Prenek on 27/07/2017.
 *
 */
public class GraphMaker extends GraphBuilder implements GraphExpander {
    private Graph<Node, GraphEdge> graph;
    private KDTree kdTree;

    private Map<TransportMode, KDTree> ingoingKDTreeMap;
    private Map<TransportMode, KDTree> outgoingKDTreeMap;
    private Map<Integer, Set<TransportMode>> nodeIncomingModes = new HashMap<>();
    private Map<Integer, Set<TransportMode>> nodeOutcomingModes = new HashMap<>();
    private int nodeCounter = 0;

    private static final Logger logger = LogManager.getLogger(GraphMaker.class);

    public Graph<Node, GraphEdge> getGraph() {
        return graph;
    }

    public Graph<Node, GraphEdge> createGraph(List<Route> routeList) {
        addRoutes(routeList);

        graph = createGraph();
        nodeCounter = 0;

        return graph;
    }

    /**
     * Graph setter from Given Graph's Nodes and Graph's Edges
     *
     * @param graph
     */
    public void setGraph(Graph<Node, GraphEdge> graph) {
        addNodes(graph.getAllNodes());
        addEdgeCollection(graph.getAllEdges());
        this.graph = createGraph();
        nodeCounter = 0;
    }

    /**
     * Graph Expansion from Routes
     *
     * @param routes Route list
     */
    private void addRoutes(List<Route> routes) {
        for (Route route : routes) {
            addLegs(route.legList);
        }
    }

    /**
     * Graph Expansion from Legs
     *
     * @param legs Leg list
     */
    private void addLegs(List<Leg> legs) {
        for (Leg leg : legs) {
            if (leg.steps.isEmpty()) {
                int startId = getIdFor(leg.startLocation);
                int endId = getIdFor(leg.endLocation);
                if (!containsEdge(startId, endId)) {
                    GraphEdge edge = new GraphEdge(startId, endId, (int) leg.durationInSeconds);
                    edge.transportMode = leg.transportMode;
                    edge.durationInSeconds = leg.durationInSeconds;
                    addEdge(edge);
                }
            } else addSteps(leg.steps);
        }
    }


    /**
     * Graph Expansion from Steps
     *
     * @param steps Step list
     */
    private void addSteps(List<Step> steps) {
        for (Step step : steps) {
            if (step.substeps != null) {
                this.addSteps(step.substeps);
                continue;
            }
            int startId = getIdFor(step.startLocation);
            int endId = getIdFor(step.endLocation);
            if (!containsEdge(startId, endId)) {
                GraphEdge edge = new GraphEdge(startId, endId, (int) step.durationInSeconds);
                edge.transportMode = step.transportMode;
                edge.durationInSeconds = step.durationInSeconds;
                addEdge(edge);
            }
        }
    }

    /**
     * Graph Expansion from Edge Sequence
     *
     * @param edges Edge Sequence
     */
    private void addEdgeCollection(Collection<GraphEdge> edges) {
        for (GraphEdge edge : edges) {
            if (!containsEdge(edge.fromId, edge.toId)) {
                addEdge(edge);
            }
        }
    }

    /**
     * Graph expansion from single Edge
     *
     * @param edge Edge
     */
    private void addEdge(GraphEdge edge) {
        super.addEdge(edge);
        int fromId = edge.fromId;
        int toId = edge.toId;
        TransportMode edgeMode = edge.transportMode;

        Set<TransportMode> set = nodeIncomingModes.get(fromId);
        if (set == null) {
            set = new HashSet<>();
        }
        set.add(edgeMode);
        nodeIncomingModes.put(fromId, set);

        set = nodeOutcomingModes.get(toId);
        if (set == null) {
            set = new HashSet<>();
        }
        set.add(edgeMode);
        nodeOutcomingModes.put(toId, set);
    }


    /**
     * Create Graph from stored Requests
     *
     * @param numOfRequests
     * @return Graph object
     */
    public Graph<Node, GraphEdge> createGraphFromKnownRequests(int numOfRequests) {
        return createGraph(expandGraphFromKnownRequests(numOfRequests));
    }

    /**
     * Create Graph from unstored Requests
     *
     * @param numOfRequests
     * @param locationArea
     * @return Graph object
     */
    public Graph<Node, GraphEdge> createGraphFromUnknownRequests(int numOfRequests, LocationArea locationArea) {
        return createGraphFromUnknownRequests(numOfRequests, locationArea, GraphExpansionStrategy.RANDOM_OD);
    }

    /**
     * Create unique Node source id based on location using Szudzik's function
     * <p>
     * See https://stackoverflow.com/questions/919612/mapping-two-integers-to-one-in-a-unique-and-deterministic-way
     *
     * @param location - location which is unique for each node
     * @return unique sourceId for given location
     */
    private static int generateSourceIdFor(Location location) {
        int a = location.latE3();
        int b = location.lonE3();
        int A = a >= 0 ? 2 * a : -2 * a - 1;
        int B = b >= 0 ? 2 * b : -2 * b - 1;

        return A >= B ? A * A + A + B : A + B * B;
    }

    public List<Route> expandGraphFromKnownRequests(int numOfRequests) {
        List<Route> resultRoutes = new ArrayList<>();
        List<Route> tmpResult;
        for (TransportMode mode : TransportMode.availableModes()) {
            for (int i = 1; i <= numOfRequests; i++) {
                tmpResult = null;
                if (mode == TransportMode.TRANSIT || mode == TransportMode.BICYCLE) {
                    tmpResult = OTPlannerAdapter.getInstance().findRoutesFromKnownRequests(i, mode);
                } else if (mode == TransportMode.CAR || mode == TransportMode.WALK) {
                    tmpResult = GMapsPlannerAdapter.getInstance().findRoutesFromKnownRequests(i, mode);
                }
                if (tmpResult == null) continue;

                resultRoutes.addAll(tmpResult);
            }
        }
        return resultRoutes;
    }


    private Graph<Node, GraphEdge> createGraphFromUnknownRequests(int numOfRequests, LocationArea locationArea,
                                                                  GraphExpansionStrategy strategy) {
        List<Route> routes = new ArrayList<>();
        List<Route> routeList;

        for (TransportMode mode : TransportMode.availableModes()) {
            // Uncomment for loop for generating more routes
            routeList = expandGraph(numOfRequests, locationArea, strategy, mode);
            routes.addAll(routeList);
        }

        return createGraph(routes);
    }

    /**
     * Graph Expansion
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param strategy Picking OD Pairs strategy
     * @return expanding route list
     */
    public List<Route> expandGraph(int numOfRequests, LocationArea locationArea, GraphExpansionStrategy strategy) {
        List<Route> routeList = new ArrayList<>();
        for (TransportMode transportMode : TransportMode.availableModes()) {
            routeList.addAll(expandGraph(numOfRequests, locationArea, strategy, transportMode));
        }
        return routeList;
    }

    /**
     * Graph Expansion
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param strategy Picking OD Pairs strategy
     * @param mode Transport Mode
     * @return expanding route list
     */
    private List<Route> expandGraph(int numOfRequests, LocationArea locationArea, GraphExpansionStrategy strategy, TransportMode mode) {
        switch (mode) {
            case BICYCLE:
            case TRANSIT:
                return expandGraph(numOfRequests, locationArea, strategy, mode, OTPlannerAdapter.getInstance());
            default:
                return expandGraph(numOfRequests, locationArea, strategy, mode, GMapsPlannerAdapter.getInstance());
        }
    }

    /**
     * Graph Expansion
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param strategy Picking OD Pairs strategy
     * @param mode Transport Mode
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     *
     */
    private List<Route> expandGraph(int numOfRequests, LocationArea locationArea,
                                    GraphExpansionStrategy strategy, TransportMode mode, PlannerAdapter plannerAdapter) {
        switch (strategy) {
            case RANDOM_OD:
                return expandGraphByRandomOD(numOfRequests, plannerAdapter, locationArea, mode);
            case CHAINING_RANDOM_OD:
                return expandGraphByChainingRandomOD(numOfRequests, plannerAdapter, locationArea, mode);
            case RANDOM_OD_WITH_MIN_DISTANCE_BETWEEN:
                return expandGraphByRandomODWithMinDistanceBetween(numOfRequests, plannerAdapter, locationArea, mode);
            case NODES_MIN_COVERAGE_UNIF_DIST:
                return expandGraphByFillingMinNodesAreaUnifDist(numOfRequests, plannerAdapter, locationArea);
            case NODES_MIN_COVERAGE_NORM_DIST:
                return expandGraphByFillingMinNodesAreaNormDist(numOfRequests, plannerAdapter, locationArea);
            case EDGES_MIN_COVERAGE_UNIF_DIST:
                return expandGraphByFillingMinEdgesAreaUnifDist(numOfRequests, locationArea, mode);
            case EDGES_MIN_COVERAGE_NORM_DIST:
                return expandGraphByFillingMinEdgesAreaNormDist(numOfRequests, locationArea, mode);
            case USING_KNOWN_NODES_AS_OD:
                return expandGraphUsingKnownNodesAsOD(numOfRequests, plannerAdapter, locationArea, mode);
        }

        return null;
    }

    /**
     * Graph Expansion using Random OD strategy
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param mode Transport Mode
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     */
    public List<Route> expandGraphByRandomOD(int numOfRequests, PlannerAdapter plannerAdapter, LocationArea locationArea,
                                             TransportMode mode) {
        Location[] locArray;
        List<Route> routes = new ArrayList<>();
        List<Route> routeList;

        for (int i = 0; i < numOfRequests; i++) {
            locArray = locationArea.generateRandomLocations(2);
            routeList = plannerAdapter.findRoutes(locArray[0], locArray[1], mode);
            routes.addAll(routeList);
            routeList = plannerAdapter.findRoutes(locArray[1], locArray[0], mode);
            routes.addAll(routeList);
        }

        return routes;
    }

    /**
     * Graph Expansion using Known Nodes as OD strategy
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param mode Transport Mode
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     */
    public List<Route> expandGraphUsingKnownNodesAsOD(int numOfRequests, PlannerAdapter plannerAdapter,
                                                      LocationArea locationArea, TransportMode mode) {
        Location[] importantPlaces = Storage.IMPORTANT_PLACES_PRAGUE;
        List<Route> routes = new ArrayList<>();
        List<Route> tmpRoutes;
        Location randLoc;


        for (Location importantPlace : importantPlaces) {
            for (int i = 0; i < 100; i++) {
                randLoc = locationArea.generateRandomLocation();
                tmpRoutes = plannerAdapter.findRoutes(importantPlace, randLoc, mode);
                routes.addAll(tmpRoutes);
                tmpRoutes = plannerAdapter.findRoutes(randLoc, importantPlace, mode);
                routes.addAll(tmpRoutes);
            }
            numOfRequests -= 100;
        }

        tmpRoutes = expandGraphByRandomOD(numOfRequests, plannerAdapter, locationArea, mode);
        routes.addAll(tmpRoutes);

        return routes;
    }

    /**
     * Graph Expansion using Chaining Random OD strategy
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param mode Transport Mode
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     */
    public List<Route> expandGraphByChainingRandomOD(int numOfRequests, PlannerAdapter plannerAdapter,
                                                     LocationArea locationArea, TransportMode mode) {
        Location locFrom, locTo;
        List<Route> routes = new ArrayList<>();
        List<Route> routeList;

        locFrom = locationArea.generateRandomLocation();

        for (int i = 0; i < numOfRequests; i++) {
            locTo = locationArea.generateRandomLocation();
            routeList = plannerAdapter.findRoutes(locFrom, locTo, mode);
            routes.addAll(routeList);
            routeList = plannerAdapter.findRoutes(locTo, locFrom, mode);
            routes.addAll(routeList);

            locFrom = locTo;
        }

        return routes;
    }

    /**
     * Graph Expansion using Random OD With Minimal Mutual Distance strategy
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param mode Transport Mode
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     */
    public List<Route> expandGraphByRandomODWithMinDistanceBetween(int numOfRequests, PlannerAdapter plannerAdapter,
                                                                   LocationArea locationArea, TransportMode mode) {
        Location[] locArray;
        List<Route> routes = new ArrayList<>();
        List<Route> routeList;

        for (int i = 0; i < numOfRequests; i++) {
            locArray = locationArea.generateODWithMinimalDistanceBetween(Storage.MIN_DISTANCE_IN_METERS_BETWEEN_OD);
            routeList = plannerAdapter.findRoutes(locArray[0], locArray[1], mode);
            routes.addAll(routeList);
            routeList = plannerAdapter.findRoutes(locArray[1], locArray[0], mode);
            routes.addAll(routeList);
        }

        return routes;
    }

    /**
     * Graph Expansion using Nodes' Uniform Distribution
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     */
    public List<Route> expandGraphByFillingMinNodesAreaUnifDist(int numOfRequests, PlannerAdapter plannerAdapter,
                                                                LocationArea locationArea) {
        int remainingRequestsCount = numOfRequests;
        List<Route> tmpRouteList = new ArrayList<>();
        Graph<Node, GraphEdge> tmpGraph;
        while (remainingRequestsCount > 0) {
            tmpRouteList.addAll(expandGraph(200, locationArea, GraphExpansionStrategy.RANDOM_OD));
            tmpGraph = createGraph(tmpRouteList);
            List<LocationArea> invalidAreaList = invalidAreasOfNodesUniformDist(tmpGraph, locationArea);
            List<Route> fillingRouteList = invalidAreaList
                    .stream()
                    .map(area -> expandGraph(50, area, GraphExpansionStrategy.RANDOM_OD))
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            tmpRouteList.addAll(fillingRouteList);

            remainingRequestsCount -= 200 + 50 * invalidAreaList.size();
        }

        return tmpRouteList;
    }

    /**
     * Graph Expansion using Nodes' Normal Distribution
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param plannerAdapter PlannerAdapter which will be used
     * @return expanding route list
     */
    public List<Route> expandGraphByFillingMinNodesAreaNormDist(int numOfRequests, PlannerAdapter plannerAdapter,
                                                                LocationArea locationArea) {
        int remainingRequestsCount = numOfRequests;
        List<Route> tmpRouteList = new ArrayList<>();
        Graph<Node, GraphEdge> tmpGraph;
        while (remainingRequestsCount > 0) {
            tmpRouteList.addAll(expandGraph(200, locationArea, GraphExpansionStrategy.RANDOM_OD));
            tmpGraph = createGraph(tmpRouteList);
            List<LocationArea> invalidAreaList = invalidAreasOfNodesNormDist(tmpGraph, locationArea);
            List<Route> fillingRouteList = invalidAreaList
                    .stream()
                    .map(area -> expandGraph(50, area, GraphExpansionStrategy.RANDOM_OD))
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            tmpRouteList.addAll(fillingRouteList);

            remainingRequestsCount -= 200 + 50 * invalidAreaList.size();
        }

        return tmpRouteList;
    }

    /**
     * Graph Expansion using Edges' Uniform Distribution
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param mode Transport mode
     * @return expanding route list
     */
    public List<Route> expandGraphByFillingMinEdgesAreaUnifDist(int numOfRequests,
                                                                LocationArea locationArea, TransportMode mode) {
        int remainingRequestsCount = numOfRequests;
        List<Route> tmpRouteList = new ArrayList<>();
        Graph<Node, GraphEdge> tmpGraph;
        while (remainingRequestsCount > 0) {
            tmpRouteList.addAll(expandGraph(200, locationArea, GraphExpansionStrategy.RANDOM_OD, mode));
            tmpGraph = createGraph(tmpRouteList);
            List<LocationArea> invalidAreaList = invalidAreasOfEdgesUniformDist(tmpGraph, locationArea, mode);
            List<Route> fillingRouteList = invalidAreaList
                    .stream()
                    .map(area -> expandGraph(50, area, GraphExpansionStrategy.RANDOM_OD, mode))
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            tmpRouteList.addAll(fillingRouteList);

            remainingRequestsCount -= 200 + 50 * invalidAreaList.size();
        }

        return tmpRouteList;
    }

    /**
     * Graph Expansion using Edges' Normal Distribution
     *
     * @param numOfRequests number Of Request
     * @param locationArea Selected Test Region
     * @param mode Transport mode
     * @return expanding route list
     */
    public List<Route> expandGraphByFillingMinEdgesAreaNormDist(int numOfRequests, LocationArea locationArea,
                                                                TransportMode mode) {
        int remainingRequestsCount = numOfRequests;
        List<Route> tmpRouteList = new ArrayList<>();
        Graph<Node, GraphEdge> tmpGraph;
        while (remainingRequestsCount > 0) {
            tmpRouteList.addAll(expandGraph(200, locationArea, GraphExpansionStrategy.RANDOM_OD, mode));
            tmpGraph = createGraph(tmpRouteList);
            List<LocationArea> invalidAreaList = invalidAreasOfEdgesNormDist(tmpGraph, locationArea, mode);
            List<Route> fillingRouteList = invalidAreaList
                    .stream()
                    .map(area -> expandGraph(50, area, GraphExpansionStrategy.RANDOM_OD, mode))
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            tmpRouteList.addAll(fillingRouteList);

            remainingRequestsCount -= 200 + 50 * invalidAreaList.size();
        }

        return tmpRouteList;
    }


    /**
     * KD Tree creation
     *
     * create seperate KD trees for each transport mode and one general for all of them
     */
    public void createKDTree() {
        logger.info("Creating KDTree...");
        double[] tmpArr = new double[2];
        kdTree = new KDTree(2);
        for (Node graphNode : graph.getAllNodes()) {
            tmpArr[0] = graphNode.getLatitude();
            tmpArr[1] = graphNode.getLongitude();
            kdTree.insert(tmpArr, graphNode.id);
        }
        ingoingKDTreeMap = createIngoingKDTreeMap();
        outgoingKDTreeMap = createOutgoingKDTreeMap();

        logger.info("KDTree created");
    }


    /**
     * Creation of KD Trees fo all ingoing transport modes
     * @return KDTree Map
     */
    @SuppressWarnings("Duplicates")
    private Map<TransportMode, KDTree> createIngoingKDTreeMap() {
        Map<TransportMode, KDTree> map = new HashMap<>();
        double[] tmpArr = new double[2];
        Arrays.stream(TransportMode.availableModes())
                .forEach(transportMode -> {
                    KDTree tmpKdTree = new KDTree(2);
                    graph.getAllNodes()
                            .stream()
                            .filter(graphNode -> nodeIncomingModes.get(graphNode.id) != null
                                    && nodeIncomingModes.get(graphNode.id).contains(transportMode)
                            )
                            .forEach(graphNode -> {
                                tmpArr[0] = graphNode.getLatitude();
                                tmpArr[1] = graphNode.getLongitude();
                                tmpKdTree.insert(tmpArr, graphNode.id);
                            });
                    map.put(transportMode, tmpKdTree);
                });
        return map;
    }

    /**
     * Creation of KD Trees fo all outgoing transport modes
     * @return KDTree Map
     */
    @SuppressWarnings("Duplicates")
    private Map<TransportMode, KDTree> createOutgoingKDTreeMap() {
        Map<TransportMode, KDTree> map = new HashMap<>();
        double[] tmpArr = new double[2];

        Arrays.stream(TransportMode.availableModes())
                .forEach(transportMode -> {
                    KDTree tmpKdTree = new KDTree(2);
                    graph.getAllNodes()
                            .stream()
                            .filter(graphNode -> nodeOutcomingModes.get(graphNode.id) != null
                                    && nodeOutcomingModes.get(graphNode.id).contains(transportMode))
                            .forEach(graphNode -> {
                                tmpArr[0] = graphNode.getLatitude();
                                tmpArr[1] = graphNode.getLongitude();
                                tmpKdTree.insert(tmpArr, graphNode.id);
                            });
                    map.put(transportMode, tmpKdTree);
                });

        return map;
    }

    /**
     * General KD Tree getter
     *
     * @return general KD Tree for all modes
     */
    public KDTree getKdTree() {
        return kdTree;
    }

    /**
     * return KD tree for given transport ingoing/outgoing transport mode
     * @param mode transport mode
     * @param isIngoingMode ingoing or outgoing mode
     * @return KDTree object
     */
    public KDTree getKdTreeForMode(TransportMode mode, boolean isIngoingMode) {
        return isIngoingMode ? ingoingKDTreeMap.get(mode) : outgoingKDTreeMap.get(mode);
    }


    /**
     * Method which return id for current location and create node whether ain't exists.
     *
     * @param location - location which is unique for each node
     * @return id for node on given location
     */
    private int getIdFor(Location location) {
        int id;
        Node node;
        try {
            id = getIntIdForSourceId(generateSourceIdFor(location));
        } catch (NullPointerException e) {
            id = nodeCounter;
            node = new Node(nodeCounter, generateSourceIdFor(location), location.lat, location.lon,
                    location.latE3(), location.lonE3(), 0);
            addNode(node);
            nodeCounter++;
        }

        return id;
    }

    /**
     * Areas, where the mask doesn't match the distribution grid
     * @param mask boolean mask
     * @param areaGrid distributionGrid
     * @return invalid Area list
     */
    private List<LocationArea> getInvalidAreasFromMask(boolean[][] mask, LocationArea[][] areaGrid) {
        List<LocationArea> invalidLocArr = new ArrayList<>();
        List<Integer> validColumns = new ArrayList<>();
        LocationArea tmpLocationArea;

        for (int i = 0; i < mask.length; i++) {
            for (int j = 0; j < mask[0].length; j++) {
                //We found bounding box and now we are gonna try to expand them
                if (!mask[i][j]) {
                    // set bounding box valid
                    tmpLocationArea = areaGrid[i][j];
                    validColumns.clear();

                    // Try to expand columns in bounding box
                    for (int k = j; k < mask[0].length; k++) {
                        if (!mask[i][k]) {
                            validColumns.add(k);
                            mask[i][k] = true;
                            tmpLocationArea.expandArea(areaGrid[i][k]);
                        } else break;
                    }

                    // Try to expand rows in bounding box
                    for (int bbRow = i + 1; bbRow < mask.length; bbRow++) {
                        int tmpValidColumnsCount = 0;
                        for (int bbCol = j; bbCol < j + validColumns.size(); bbCol++) {
                            if (!mask[bbRow][bbCol]) tmpValidColumnsCount++;
                        }
                        // all columns are invalid -> set them valid and expand bounding box
                        if (tmpValidColumnsCount == validColumns.size()) {
                            for (int bbCol = j; bbCol < j + validColumns.size(); bbCol++) {
                                mask[bbRow][bbCol] = true;
                                tmpLocationArea.expandArea(areaGrid[bbRow][bbCol]);
                            }
                        }
                        // some columns are valid -> area would be concave, if we add only some elements -> se
                        else {
                            invalidLocArr.add(tmpLocationArea);
                            break;
                        }
                    }
                }
            }
        }

        if (!containsOnlyValidAreas(mask)) throw new RuntimeException("Invalid Area getter not implemented correctly");

        return invalidLocArr;
    }

    /**
     * Check if the distribution is valid on each subarea
     *
     * @param mask validity mask
     * @return #true when distribution is valid, #false otherwise
     */
    private boolean containsOnlyValidAreas(boolean[][] mask) {
        for (boolean[] row : mask) {
            for (boolean isValidArea : row) {
                if (!isValidArea) return false;
            }
        }
        return true;
    }


    /**
     * Invalid areas of Node Current Distribution compared to Node Uniform Distribution
     *
     * @param tmpGraph     Graph
     * @param locationArea area, where uniform distribution is checked
     * @return subareas, where is invalid uniform distribution
     */
    private List<LocationArea> invalidAreasOfNodesUniformDist(Graph<Node, GraphEdge> tmpGraph, LocationArea locationArea) {
        int gridX = Storage.GRAPH_DISTRIBUTION_GRID_X;
        int gridY = Storage.GRAPH_DISTRIBUTION_GRID_Y;
        LocationArea[][] areaGrid = locationArea.createGrid(gridX, gridY);
        List<Node> nodeList = (List<Node>) tmpGraph.getAllNodes();

        int[][] uniformDistribution = createUniformDistributionOnGrid(areaGrid, nodeList.size());
        int[][] currentDistribution = getNodeDistributionOnGrid(nodeList, areaGrid);

        boolean[][] mask = checkIfDistributionIsValid(currentDistribution, uniformDistribution);

        return getInvalidAreasFromMask(mask, areaGrid);
    }

    /**
     * Invalid areas of Node Current Distribution compared to Node Normal Distribution
     *
     * @param tmpGraph Graph object
     * @param locationArea area, where normal distribution is checked
     * @return subareas, where is invalid normal distribution
     */
    private List<LocationArea> invalidAreasOfNodesNormDist(Graph<Node, GraphEdge> tmpGraph, LocationArea locationArea) {
        int gridX = Storage.GRAPH_DISTRIBUTION_GRID_X;
        int gridY = Storage.GRAPH_DISTRIBUTION_GRID_Y;
        List<Node> nodeList = (List<Node>) tmpGraph.getAllNodes();
        int numOfNodes = nodeList.size();

        LocationArea[][] areaGrid = locationArea.createGrid(gridX, gridY);

        int[][] normDistribution = createNormalDistributionOnGrid(areaGrid, numOfNodes);
        int[][] currentDistribution = getNodeDistributionOnGrid(nodeList, areaGrid);

        boolean[][] mask = checkIfDistributionIsValid(currentDistribution, normDistribution);

        return getInvalidAreasFromMask(mask, areaGrid);

    }

    /**
     * Uniform Distribution on distribution grid
     *
     * @param areaGrid distribution Grid
     * @param elementCount number of elements
     * @return uniform distribution
     */
    private int[][] createUniformDistributionOnGrid(LocationArea[][] areaGrid, int elementCount) {
        int gridX = areaGrid.length;
        int gridY = areaGrid[0].length;
        int numOfNodesPerCell = elementCount / gridX * gridY;

        int[][] uniformDistribution = new int[gridX][gridY];
        for (int[] row : uniformDistribution) {
            Arrays.fill(row, numOfNodesPerCell);
        }

        return uniformDistribution;
    }

    /**
     * Normal Distribution on distribution grid
     *
     * @param areaGrid distribution Grid
     * @param elementCount number of elements
     * @return normal distribution
     */
    private int[][] createNormalDistributionOnGrid(LocationArea[][] areaGrid, int elementCount) {
        int gridRowsCount = areaGrid.length;
        int gridColumnsCount = areaGrid[0].length;

        int[][] gridNormDistribution = new int[gridRowsCount][gridColumnsCount];

        double[] means = new double[]{0, 0};
        double[][] covariances = new double[][]{{1, 0}, {0, 1}};
        MultivariateNormalDistribution distribution = new MultivariateNormalDistribution(means, covariances);

        double rowStepSize = 4 / (double) gridRowsCount;
        double columnStepSize = 4 / (double) gridColumnsCount;

        double val_j;
        double val_i = -2;

        for (int i = 0; i < gridRowsCount; i++, val_i += rowStepSize) {
            val_j = -2;
            for (int j = 0; j < gridColumnsCount; j++, val_j += columnStepSize) {
                gridNormDistribution[i][j] = (int) (elementCount * distribution.density(new double[]{val_i, val_j}));
            }
        }

        return gridNormDistribution;
    }

    /**
     * Check whether the current distribution match the selected one
     * @param distribution current distribution
     * @param patternDistribution selected distribution
     * @return bool 2d array
     */
    private boolean[][] checkIfDistributionIsValid(int[][] distribution, int[][] patternDistribution) {
        boolean[][] mask = new boolean[distribution.length][distribution[0].length];

        for (int i = 0; i < distribution.length; i++) {
            for (int j = 0; j < distribution[0].length; j++) {
                int diff = distribution[i][j] - patternDistribution[i][j];
                mask[i][j] = diff > 0;
            }
        }

        return mask;
    }

    /**
     * Edge Distribution of graph on Distribution Grid
     *
     * @param edgeList Graph's Edges
     * @param areaGrid Distribution Grid
     * @return edge distribution
     */
    private int[][] getEdgeDistributionOnGrid(List<GraphEdge> edgeList, LocationArea[][] areaGrid) {
        if (areaGrid == null || areaGrid[0].length == 0) return null;

        int[][] edgeDistribution = new int[areaGrid.length][areaGrid[0].length];

        for (int[] row : edgeDistribution) {
            Arrays.fill(row, 0);
        }

        for (GraphEdge edge : edgeList) {
            for (int i = 0; i < areaGrid.length; i++) {
                for (int j = 0; j < areaGrid[0].length; j++) {
                    if (isEdgeIntersectArea(edge, areaGrid[i][j])) edgeDistribution[i][j]++;
                }
            }
        }

        return edgeDistribution;
    }

    /**
     * Node Distribution of graph on Distribution Grid
     *
     * @param nodeList Graph's nodes
     * @param areaGrid distribution grid
     * @return node distribution
     */
    private int[][] getNodeDistributionOnGrid(List<Node> nodeList, LocationArea[][] areaGrid) {
        if (areaGrid == null || areaGrid[0].length == 0) return null;

        LocationArea tmpCell;
        int tmpRow, tmpColumn;
        int[][] nodeDistribution = new int[areaGrid.length][areaGrid[0].length];

        for (int[] row : nodeDistribution) {
            Arrays.fill(row, 0);
        }

        for (Node node : nodeList) {
            tmpRow = tmpColumn = 0;
            Location nodeLocation = LocationUtils.getNodeLocation(node);
            while (true) {
                tmpCell = areaGrid[tmpRow][tmpColumn];
                if (tmpCell.containsLocation(nodeLocation)) {
                    nodeDistribution[tmpRow][tmpColumn]++;
                    break;
                } else {
                    if (nodeLocation.lat > tmpCell.bottomLat) {
                        tmpRow++;
                    }
                    if (nodeLocation.lon > tmpCell.rightLon) {
                        tmpColumn++;
                    }
                }
            }
        }
        return nodeDistribution;
    }



    /**
     * Invalid areas of Current Distribution compared to Edge Normal Distribution
     *
     * @param graph Graph object
     * @param locationArea area, where unform distribution is checked
     * @param mode Transport mode that edges have
     * @return subareas, where is invalid normal distribution
     */
    private List<LocationArea> invalidAreasOfEdgesNormDist(Graph<Node, GraphEdge> graph, LocationArea locationArea, TransportMode mode) {
        int gridX = Storage.GRAPH_DISTRIBUTION_GRID_X;
        int gridY = Storage.GRAPH_DISTRIBUTION_GRID_Y;
        List<GraphEdge> edgeList = graph.getAllEdges()
                .stream()
                .filter(e -> e.transportMode == mode)
                .collect(Collectors.toList());

        LocationArea[][] areaGrid = locationArea.createGrid(gridX, gridY);

        int[][] normalDistribution = createNormalDistributionOnGrid(areaGrid, edgeList.size());
        int[][] currentDistribution = getEdgeDistributionOnGrid(edgeList, areaGrid);

        boolean[][] mask = checkIfDistributionIsValid(currentDistribution, normalDistribution);

        return getInvalidAreasFromMask(mask, areaGrid);
    }

    /**
     * Invalid areas of Current Distribution compared to Edge Uniform Distribution
     *
     * @param graph Graph object
     * @param locationArea area, where unform distribution is checked
     * @return subareas, where is invalid normal distribution
     */
    private List<LocationArea> invalidAreasOfEdgesUniformDist(Graph<Node, GraphEdge> graph, LocationArea locationArea, TransportMode mode) {
        int gridX = Storage.GRAPH_DISTRIBUTION_GRID_X;
        int gridY = Storage.GRAPH_DISTRIBUTION_GRID_Y;
        List<GraphEdge> edgeList = graph.getAllEdges()
                .stream()
                .filter(e -> e.transportMode == mode)
                .collect(Collectors.toList());

        LocationArea[][] areaGrid = locationArea.createGrid(gridX, gridY);

        int[][] uniformDistribution = createUniformDistributionOnGrid(areaGrid, edgeList.size());
        int[][] currentDistribution = getEdgeDistributionOnGrid(edgeList, areaGrid);

        boolean[][] mask = checkIfDistributionIsValid(currentDistribution, uniformDistribution);

        return getInvalidAreasFromMask(mask, areaGrid);
    }

    /**
     * Check whether the Edge goes through given area
     * @param edge Graph's Edge
     * @param area Area
     * @return #true when intersect, #false otherwise
     */
    private boolean isEdgeIntersectArea(GraphEdge edge, LocationArea area) {
        Node nodeFrom = graph.getNode(edge.fromId);
        Node nodeTo = graph.getNode(edge.toId);

        Location locFrom = LocationUtils.getNodeLocation(nodeFrom);
        Location locTo = LocationUtils.getNodeLocation(nodeTo);

        Rectangle2D r1 = new Rectangle2D.Double(area.leftLon, area.bottomLat, area.rightLon - area.leftLon, area.upLat - area.bottomLat);
        Line2D l1 = new Line2D.Double(locFrom.lon, locFrom.lat, locTo.lon, locTo.lat);

        return l1.intersects(r1);
    }
}
