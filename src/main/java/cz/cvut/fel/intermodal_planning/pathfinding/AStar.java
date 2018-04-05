package cz.cvut.fel.intermodal_planning.pathfinding;

import com.umotional.basestructures.Graph;
import com.umotional.basestructures.Node;
import cz.cvut.fel.intermodal_planning.planner.RoutePlanner;
import cz.cvut.fel.intermodal_planning.model.graph.GraphEdge;
import cz.cvut.fel.intermodal_planning.model.planner.Location;
import cz.cvut.fel.intermodal_planning.model.planner.TransportMode;
import cz.cvut.fel.intermodal_planning.utils.LocationUtils;

import java.util.*;
import java.util.stream.Collectors;

public class AStar<TNode extends Node> {

    private final Graph<TNode, GraphEdge> graph;

    private HashSet<Integer> closedList;
    private FibonacciHeap<TNode> openList;
    private List<GraphEdge> path;
    private Map<Integer, Integer> prevNodes;


    public AStar(Graph<TNode, GraphEdge> graph) {
        this.graph = graph;

        openList = new FibonacciHeap<>();
        closedList = new HashSet<>();
        path = new ArrayList<>();
        prevNodes = new HashMap<>();
    }

    public List<GraphEdge> plan(Location originLocation, List<TNode> originNodes, List<TNode> destinationNodes,
                                TransportMode... availableModes) {
        List availableModesList = Arrays.asList(availableModes);
        FibonacciHeap.Entry<TNode> entry_from;
        FibonacciHeap.Entry<TNode> entry_old;

        TNode node_to;
        List<GraphEdge> list;
        double priority_new;
        TransportMode prevMode = null;

        openList = new FibonacciHeap<>();
        closedList.clear();
        path.clear();
        prevNodes.clear();

        originNodes.stream().forEach(originNode -> openList.enqueue(originNode,
                LocationUtils.walkDuration(Location.getLocation(originNode), originLocation)));


        while (!openList.isEmpty()) {
            entry_from = openList.dequeueMin();

            //we find the DESTINATION NODE! Now, we have to backtrack the path
            if (destinationNodes.contains(entry_from.getValue())) {
                return findPath(graph, originNodes, entry_from.getValue());
            }

            closedList.add(entry_from.getValue().id);

            //there is an option, that list of outcoming edges is empty- that's why there is try/catch
            try {
                list = graph.getOutEdges(entry_from.getValue().id);

                // loop all edges from dequeued node
                for (GraphEdge edge : list) {
                    //if node is in closed list
                    // or is not allowed to use a specified transport mode then continue
                    // or is not transfer possible
                    if (closedList.contains(edge.toId)
                            || !availableModesList.contains(edge.mode)
                            || !RoutePlanner.isTransferPossible(prevMode, edge.mode)
                            ) {
                        continue;
                    }
                    node_to = graph.getNode(edge.toId);

                    int transferPenalty = (prevMode != null && prevMode != edge.mode) ?
                            RoutePlanner.getTransferPenalty(prevMode) : 0;

                    // get cost of start node, we substract start-node's distance and after that we add end-node's distance
                    // and we also add edge length divided by allowed speed
                    priority_new = entry_from.getPriority() + edge.durationInSeconds + transferPenalty;

                    entry_old = openList.getEntry(node_to);
                    //node is in the open list, so we have to compare priority and choose the better ones
                    if (entry_old != null) {
                        if (entry_old.getPriority() > priority_new) {
                            openList.decreaseKey(entry_old, priority_new);
                            prevNodes.put(node_to.id, entry_from.getValue().id);
                            prevMode = edge.mode;
                        }
                    } else {
                        //if node is not in the open list, then we have to add it there
                        prevNodes.put(node_to.id, entry_from.getValue().id);
                        // do something
                        openList.enqueue(node_to, priority_new);
                        prevMode = edge.mode;
                    }
                }
            } catch (NullPointerException e) {

            }

        }
        return null;
    }

    public List<GraphEdge> plan(Location origin, List<TNode> originNode, List<TNode> destinationNode) {
        return plan(origin, originNode, destinationNode, TransportMode.availableModes());

    }

    private List<GraphEdge> findPath(Graph<TNode, GraphEdge> graph, List<TNode> originList, TNode destination) {
        LinkedList<GraphEdge> path = new LinkedList<>();
        int tmpId = destination.id;

        List<Integer> originIdList = originList.stream().map(origin -> origin.id).collect(Collectors.toList());

        while (originIdList.contains(tmpId)) {
            GraphEdge edge = graph.getEdge(prevNodes.get(tmpId), tmpId);
            path.addFirst(edge);
            tmpId = prevNodes.get(tmpId);
        }

        return path;
    }

    public boolean equals(TNode o1, TNode o2) {
        return o1.id == o2.id;
    }
}