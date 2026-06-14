package dev.shinpei.transitboard.api;

import dev.shinpei.transitboard.model.ObaResponse;

import java.util.ArrayList;
import java.util.List;

public class SiblingResolver {

    /**
     * Resolves sibling stop IDs for a given stop.
     *
     * @param currentStopId the ID of the current stop
     * @param parentId      the parent stop ID (may be null or empty)
     * @param allStops      all stops from the OBA response references
     * @return list of sibling stop IDs (children of parent, excluding currentStopId)
     */
    public static List<String> resolve(String currentStopId, String parentId, List<ObaResponse.Stop> allStops) {
        if (parentId == null || parentId.isEmpty() || allStops == null) {
            return List.of();
        }

        List<String> siblings = new ArrayList<>();
        for (ObaResponse.Stop stop : allStops) {
            if (parentId.equals(stop.parent) && !currentStopId.equals(stop.id)) {
                siblings.add(stop.id);
            }
        }
        return siblings;
    }
}
