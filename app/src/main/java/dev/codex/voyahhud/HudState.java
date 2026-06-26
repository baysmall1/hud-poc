package dev.codex.voyahhud;

import java.util.ArrayList;
import java.util.List;

final class HudState {
    boolean navigating;
    String currentRoad = "";
    String nextRoad = "";
    String direction = "";
    int turnKind;
    String turnIconResource = "";
    int maneuverDistance = -1;
    String maneuverDistanceText = "";
    String maneuverDistanceUnit = "";
    String immediateGuideText = "";
    int totalDistance = -1;
    int totalTimeSeconds = -1;
    int speed = -1;
    int speedLimit = -1;
    byte[] enlargeImage;
    long enlargeUpdatedAt;
    long guideUpdatedAt;
    final List<Lane> lanes = new ArrayList<>();
    int trafficLightColor;
    int trafficLightSeconds;

    void clearNavigation() {
        navigating = false;
        currentRoad = "";
        nextRoad = "";
        direction = "";
        turnKind = 0;
        turnIconResource = "";
        maneuverDistance = -1;
        maneuverDistanceText = "";
        maneuverDistanceUnit = "";
        immediateGuideText = "";
        totalDistance = -1;
        totalTimeSeconds = -1;
        speedLimit = -1;
        enlargeImage = null;
        enlargeUpdatedAt = 0;
        guideUpdatedAt = 0;
        lanes.clear();
        trafficLightColor = 0;
        trafficLightSeconds = 0;
    }

    String renderKey() {
        StringBuilder key = new StringBuilder(192)
                .append(navigating).append('|').append(currentRoad).append('|')
                .append(nextRoad).append('|').append(direction).append('|')
                .append(turnKind).append('|').append(turnIconResource).append('|')
                .append(maneuverDistance).append('|')
                .append(maneuverDistanceText).append('|').append(maneuverDistanceUnit).append('|')
                .append(immediateGuideText).append('|')
                .append(totalDistance).append('|').append(totalTimeSeconds).append('|')
                .append(speed).append('|').append(speedLimit)
                .append('|').append(enlargeUpdatedAt);
        key.append('|').append(trafficLightColor).append('|').append(trafficLightSeconds);
        for (Lane lane : lanes) {
            key.append('|').append(lane.resourceName);
        }
        return key.toString();
    }

    static final class Lane {
        final String resourceName;

        Lane(String resourceName) {
            this.resourceName = resourceName;
        }
    }
}
