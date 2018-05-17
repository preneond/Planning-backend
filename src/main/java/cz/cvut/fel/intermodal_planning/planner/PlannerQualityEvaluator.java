package cz.cvut.fel.intermodal_planning.planner;

import cz.cvut.fel.intermodal_planning.general.Storage;
import cz.cvut.fel.intermodal_planning.graph.enums.GraphExpansionStrategy;
import cz.cvut.fel.intermodal_planning.planner.model.Location;
import cz.cvut.fel.intermodal_planning.planner.model.LocationArea;
import cz.cvut.fel.intermodal_planning.planner.model.Route;
import cz.cvut.fel.intermodal_planning.planner.model.TransportMode;
import cz.cvut.fel.intermodal_planning.general.utils.GeoJSONBuilder;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

public class PlannerQualityEvaluator {
    private static final Logger logger = LogManager.getLogger(PlannerInitializer.class);

//    public static void evaluatePlannerQuality(PlannerInitializer plannerInitializer, GraphQualityMetric qualityMetric) {
////        logger.info("Checking planner using " + plannerInitializer.expansionStrategy.name() + " strategy by " + qualityMetric.name() + "quality metrix");
//        switch (qualityMetric) {
//            case REFINEMENT:
//                evaluatePlannerQualityUsingRefinement(plannerInitializer);
//                break;
//            case SINGLEMODAL:
////                evaluatePlannerQualityUsingSinglemodalQualityCheck(plannerInitializer);
//                break;
//        }
//    }

    private static void evaluatePlannerQualityUsingSinglemodalQualityCheck(PlannerInitializer plannerInitializer) {
        int findingPathCount = Storage.FINDING_PATH_COUNT;
        long[] routeDuration, subplannerRouteDuration, deviation;
        double[] modeDeviation = new double[TransportMode.availableModes().length];
        File file = new File(Storage.STATISTICS_PATH + "/planner_quality_singlemodal.txt");

        try {
            FileWriter writer = new FileWriter(file, true);

            int modeCount = 0;
            for (TransportMode transportMode : TransportMode.singleModalModes()) {
                routeDuration = new long[findingPathCount];
                subplannerRouteDuration = new long[findingPathCount];
                deviation = new long[findingPathCount];

                for (int i = 0; i < findingPathCount; i++) {
                    Location[] locArray = plannerInitializer.locationArea.generateRandomLocations(2);
                    Route plannerRoute = plannerInitializer.routePlanner.findRoute(locArray[0], locArray[1], transportMode);
                    Route subplannerRoute = plannerInitializer.routePlanner.findRouteBySubplanner(locArray[0], locArray[1], transportMode);

                    routeDuration[i] = plannerInitializer.routePlanner.getRouteDuration(plannerRoute);
                    subplannerRouteDuration[i] = plannerInitializer.routePlanner.getRouteDuration(subplannerRoute);
                    deviation[i] = routeDuration[i] - subplannerRouteDuration[i];
                }
                modeDeviation[modeCount++] = Arrays.stream(deviation).average().orElse(0);

            }

            double avgDeviation = Arrays.stream(modeDeviation).sum();

            writer.write(plannerInitializer.requestCount + " requests, " +
                    "strategy: " + plannerInitializer.expansionStrategy.name() + "," +
                    "deviation sum: " + avgDeviation + " s\n");

            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void evaluatePlannerQualityUsingRefinement(RoutePlanner routePlanner, LocationArea locationArea,
                                                             GraphExpansionStrategy expansionStrategy, int requestCount) {
        double sizeDeviation;
        int findingPathCount = Storage.FINDING_PATH_COUNT;

        long[] routeDuration = new long[findingPathCount];
        long[] refinementRouteDuration = new long[findingPathCount];
        long[] deviation = new long[findingPathCount];

        for (int i = 0; i < findingPathCount; i++) {
            Route graphPath = routePlanner.findRandomRoute(locationArea);
            Route refinementRoute = routePlanner.doRefinement(graphPath);

            routeDuration[i] = routePlanner.getRouteDuration(graphPath);
            refinementRouteDuration[i] = routePlanner.getRouteDuration(refinementRoute);
            deviation[i] = routeDuration[i] - refinementRouteDuration[i];
        }

        sizeDeviation = Arrays.stream(deviation).sum();

        File file = new File(Storage.STATISTICS_PATH + "/planner_quality_refinement.txt");
        try {
            FileWriter writer = new FileWriter(file, true);
            writer.write(requestCount + "requests," +
                    "strategy: " + expansionStrategy.name() + "," +
                    "deviation sum: " + sizeDeviation + " s\n");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void compareNormalRefinementPaths(PlannerInitializer plannerInitializer) {
        RoutePlanner routePlanner = plannerInitializer.routePlanner;

        Route route = routePlanner.findRandomRoute(plannerInitializer.locationArea);
        Route refoundedRoute = routePlanner.doRefinement(route);

        System.out.println("Route duration: " + routePlanner.getRouteDuration(route));
        System.out.println("Refounded route duration: " + routePlanner.getRouteDuration(refoundedRoute));

        System.out.println(GeoJSONBuilder.getInstance().buildGeoJSONStringForRoute(route));
        System.out.println(GeoJSONBuilder.getInstance().buildGeoJSONStringForRoute(refoundedRoute));
    }
}
