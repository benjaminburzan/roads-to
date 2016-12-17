/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper GmbH licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader.gtfs;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.graphhopper.routing.weighting.TimeDependentWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;

/**
 * Implements a Multi-Criteria Label Setting (MLS) path finding algorithm with
 * the criteria earliest arrival time and number of transfers.
 * <p>
 *
 * @author Michael Zilske
 * @author Peter Karich
 */
class MultiCriteriaLabelsSetting {

	private final PtFlagEncoder flagEncoder;
	private final Weighting weighting;
	private final SetMultimap<Integer, Label> fromMap;
	private final PriorityQueue<Label> fromHeap;
	private final int maxVisitedNodes;
	private final boolean reverse;
	private long rangeQueryEndTime;
	private int visitedNodes;
	private final GraphExplorer explorer;
	private int nodes;

	MultiCriteriaLabelsSetting(Graph graph, Weighting weighting,
			int maxVisitedNodes, GraphExplorer explorer, boolean reverse) {
		this.weighting = weighting;
		this.flagEncoder = (PtFlagEncoder) weighting.getFlagEncoder();
		this.maxVisitedNodes = maxVisitedNodes;
		this.explorer = explorer;
		this.reverse = reverse;
		this.nodes = graph.getNodes();
		int size = Math.min(Math.max(200, graph.getNodes() / 10), 2000);
		fromHeap = new PriorityQueue<>(size, new Comparator<Label>() {
			@Override
			public int compare(Label o1, Label o) {
				if (!reverse && o1.currentTime < o.currentTime)
					return -1;
				else if (reverse && o1.currentTime > o.currentTime)
					return -1;
				else if (!reverse && o1.currentTime > o.currentTime)
					return 1;
				else if (reverse && o1.currentTime < o.currentTime)
					return 1;
				else if (o1.nTransfers < o.nTransfers)
					return -1;
				else if (o1.nTransfers > o.nTransfers)
					return 1;
				else if (!reverse
						&& o1.firstPtDepartureTime < o.firstPtDepartureTime)
					return -1;
				else if (reverse
						&& o1.firstPtDepartureTime > o.firstPtDepartureTime)
					return -1;
				else if (!reverse
						&& o1.firstPtDepartureTime > o.firstPtDepartureTime)
					return 1;
				else if (reverse
						&& o1.firstPtDepartureTime < o.firstPtDepartureTime)
					return 1;
				return 0;
			}

			@Override
			public boolean equals(Object obj) {
				return false;
			}
		});
		fromMap = HashMultimap.create();
	}

	Collection<Label> calcLabels(int from, long startTime,
			long rangeQueryEndTime) {
		Set<Integer> to = Collections.emptySet();
		this.rangeQueryEndTime = rangeQueryEndTime;
		Set<Label> targetLabels = new HashSet<>();
		Label label = new Label(startTime, EdgeIterator.NO_EDGE, from, 0,
				Long.MAX_VALUE, null);
		fromMap.put(from, label);
		if (to.contains(from)) {
			targetLabels.add(label);
		}
		while (true) {
			visitedNodes++;
			if (visitedNodes % 1000 == 0) {
				System.err.println(MessageFormat.format(
						"Visited nodes: {0}/{1}", visitedNodes, nodes));
			}
			if (maxVisitedNodes < visitedNodes)
				break;

			for (EdgeIteratorState edge : explorer.exploreEdgesAround(label)) {
				GtfsStorage.EdgeType edgeType = flagEncoder.getEdgeType(edge
						.getFlags());
				int tmpNTransfers = label.nTransfers;
				long tmpFirstPtDepartureTime = label.firstPtDepartureTime;
				long nextTime;
				if (reverse) {
					nextTime = label.currentTime
							- ((TimeDependentWeighting) weighting)
									.calcTravelTimeSeconds(edge,
											label.currentTime);
				} else {
					nextTime = label.currentTime
							+ ((TimeDependentWeighting) weighting)
									.calcTravelTimeSeconds(edge,
											label.currentTime);
				}
				tmpNTransfers += ((TimeDependentWeighting) weighting)
						.calcNTransfers(edge);
				if (!reverse && edgeType == GtfsStorage.EdgeType.BOARD
						&& tmpFirstPtDepartureTime == Long.MAX_VALUE) {
					tmpFirstPtDepartureTime = nextTime;
				}
				if (reverse
						&& edgeType == GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK
						&& tmpFirstPtDepartureTime == Long.MAX_VALUE) {
					tmpFirstPtDepartureTime = nextTime;
				}

				Set<Label> sptEntries = fromMap.get(edge.getAdjNode());
				Label nEdge = new Label(nextTime, edge.getEdge(),
						edge.getAdjNode(), tmpNTransfers,
						tmpFirstPtDepartureTime, label);
				if (improves(nEdge, sptEntries)
						&& improves(nEdge, targetLabels)) {
					removeDominated(nEdge, sptEntries);
					if (to.contains(edge.getAdjNode())) {
						removeDominated(nEdge, targetLabels);
					}
					fromMap.put(edge.getAdjNode(), nEdge);
					if (to.contains(edge.getAdjNode())) {
						targetLabels.add(nEdge);
					}
					fromHeap.add(nEdge);
				}
			}

			if (fromHeap.isEmpty())
				break;

			label = fromHeap.poll();
			if (label == null)
				throw new AssertionError("Empty edge cannot happen");
		}

		return fromMap.values();
	}

	private boolean improves(Label me, Set<Label> sptEntries) {
		for (Label they : sptEntries) {
			if (they.nTransfers <= me.nTransfers
					&& (reverse ? they.currentTime >= me.currentTime
							: they.currentTime <= me.currentTime)
					&& (reverse ? (they.firstPtDepartureTime <= me.firstPtDepartureTime || me.firstPtDepartureTime < rangeQueryEndTime)
							: (they.firstPtDepartureTime >= me.firstPtDepartureTime || me.firstPtDepartureTime > rangeQueryEndTime))) {
				return false;
			}
		}
		return true;
	}

	private void removeDominated(Label me, Set<Label> sptEntries) {
		for (Iterator<Label> iterator = sptEntries.iterator(); iterator
				.hasNext();) {
			Label sptEntry = iterator.next();
			if (dominates(me, sptEntry)) {
				fromHeap.remove(sptEntry);
				iterator.remove();
			}
		}
	}

	private boolean dominates(Label me, Label they) {
		if (reverse) {
			if (me.currentTime < they.currentTime) {
				return false;
			}
		} else {
			if (me.currentTime > they.currentTime) {
				return false;
			}
		}
		if (me.nTransfers > they.nTransfers) {
			return false;
		}
		if (reverse) {
			if (me.firstPtDepartureTime > they.firstPtDepartureTime) {
				return false;
			}
		} else {
			if (me.firstPtDepartureTime < they.firstPtDepartureTime) {
				return false;
			}
		}
		if (reverse) {
			if (me.currentTime > they.currentTime) {
				return true;
			}
		} else {
			if (me.currentTime < they.currentTime) {
				return true;
			}
		}
		if (me.nTransfers < they.nTransfers) {
			return true;
		}
		if (reverse) {
			if (me.firstPtDepartureTime < they.firstPtDepartureTime) {
				return true;
			}
		} else {
			if (me.firstPtDepartureTime > they.firstPtDepartureTime) {
				return true;
			}
		}
		return false;
	}

	int getVisitedNodes() {
		return visitedNodes;
	}

}
