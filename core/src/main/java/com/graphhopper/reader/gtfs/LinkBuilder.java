package com.graphhopper.reader.gtfs;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;

public class LinkBuilder {

	private final GraphHopperStorage storage;

	public LinkBuilder(GraphHopperStorage storage) {
		this.storage = storage;
	}

	private Map<PointList, Label> labels = new HashMap<>();

	private Map<PointList, Link> links = new HashMap<>();

	public void addLabel(Label label) {
		if (label.edge == -1) {
			return;
		}

		final EdgeIteratorState state = storage.getEdgeIteratorState(
				label.edge, label.adjNode);
		final PointList geometry = state.fetchWayGeometry(3);

		double lastLat = geometry.getLat(geometry.getSize() - 1);
		double lastLon = geometry.getLon(geometry.getSize() - 1);
		final PointList lastPoint = new PointList(1, false);
		lastPoint.add(lastLat, lastLon);

		labels.compute(lastPoint, (key, oldLabel) -> {
			if (oldLabel == null) {
				return label;
			} else if (label.currentTime < oldLabel.currentTime) {

				return label;
			} else {
				return oldLabel;
			}
		});
	}

	public Collection<Link> buildLinks() {
		this.labels.values().forEach(this::buildLinks);
		return this.links.values();
	}

	private void buildLinks(Label label) {
		if (label == null || label.edge == -1) {
			return;
		}
		long startTime;
		long endTime;
		final PointList pointList = new PointList();
		int numberOfTransfers = 0;

		double lastLat = Double.NaN;
		double lastLon = Double.NaN;
		Label currentLabel = label;
		endTime = label.currentTime;
		do {
			startTime = label.currentTime;
			numberOfTransfers += label.nTransfers;
			final EdgeIteratorState state = storage.getEdgeIteratorState(
					label.edge, label.adjNode);
			final PointList geometry = state.fetchWayGeometry(3);
			for (int index = geometry.getSize() - 1; index >= 0; index--) {
				double currentLat = geometry.getLat(index);
				double currentLon = geometry.getLon(index);
				if (Double.doubleToLongBits(currentLat) != Double
						.doubleToLongBits(lastLat)
						&& Double.doubleToLongBits(currentLon) != Double
								.doubleToLongBits(lastLon)) {
					pointList.add(currentLat, currentLon);
				}
				lastLat = currentLat;
				lastLon = currentLon;

			}
			currentLabel = currentLabel.parent;
		} while (currentLabel != null && currentLabel.edge != -1
				&& pointList.size() < 2);

		pointList.reverse();
		final Link link = new Link(startTime, endTime, numberOfTransfers,
				pointList);

		links.put(link.getPointList(), link);
		buildLinks(currentLabel);
	}

}