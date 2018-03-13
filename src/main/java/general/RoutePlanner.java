package general;

import adapters.GMapsPlannerAdapter;
import adapters.OpenTripPlannerAdapter;
import adapters.PlannerAdapter;
import model.planner.Location;
import model.planner.Route;
import utils.RandomLocationGenerator;

import java.util.ArrayList;
import java.util.List;

public class RoutePlanner {
    private PlannerAdapter[] plannerAdapters;

    public static final short NUM_OF_PATHS = 50;
    
    public RoutePlanner() {
        // add more planner adapters if they exist
        plannerAdapters = new PlannerAdapter[]{new GMapsPlannerAdapter(), new OpenTripPlannerAdapter()};
    }

    public void findRoute() {
        Location[] locArray;
        List<Route> routes = new ArrayList<>();
        List<Route> routeList;

        for (PlannerAdapter plannerAdapter : plannerAdapters) {
            // Uncomment for loop for generating more routes
            for (int i = 0; i < NUM_OF_PATHS; i++) {
                locArray = RandomLocationGenerator.getInstance().generateLocationsInPrague(2);
                routeList = plannerAdapter.findRoutes(locArray[0], locArray[1]);
                routes.addAll(routeList);
                routeList = plannerAdapter.findRoutes(locArray[1], locArray[0]);
                routes.addAll(routeList);
            }
        }
        GraphMaker graphMaker = GraphMaker.getInstance();
        graphMaker.createGraph(routes);
    }
}
