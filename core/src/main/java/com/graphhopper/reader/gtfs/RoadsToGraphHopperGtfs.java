package com.graphhopper.reader.gtfs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.conveyal.gtfs.GTFSFeed;
import com.graphhopper.GHRequest;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.DAType;
import com.graphhopper.storage.GHDirectory;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.Helper;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.Unzipper;
import com.graphhopper.util.shapes.GHPoint;

public final class RoadsToGraphHopperGtfs {

	public static final String EARLIEST_DEPARTURE_TIME_HINT = "earliestDepartureTime";
	public static final String RANGE_QUERY_END_TIME = "rangeQueryEndTime";
	public static final String ARRIVE_BY = "arriveBy";

	private final TranslationMap translationMap;
	private final EncodingManager encodingManager;

	private GraphHopperStorage graphHopperStorage;
	private LocationIndex locationIndex;
	private GtfsStorage gtfsStorage;

	public RoadsToGraphHopperGtfs(EncodingManager encodingManager,
			TranslationMap translationMap,
			GraphHopperStorage graphHopperStorage, LocationIndex locationIndex,
			GtfsStorage gtfsStorage) {
		this.encodingManager = encodingManager;
		this.translationMap = translationMap;
		this.graphHopperStorage = graphHopperStorage;
		this.locationIndex = locationIndex;
		this.gtfsStorage = gtfsStorage;
	}

	public static RoadsToGraphHopperGtfs createGraphHopperGtfs(
			String graphHopperFolder, String gtfsFile, boolean createWalkNetwork) {
		EncodingManager encodingManager = createEncodingManager();

		if (Helper.isEmpty(graphHopperFolder))
			throw new IllegalStateException(
					"GraphHopperLocation is not specified. Call setGraphHopperLocation or init before");

		if (graphHopperFolder.endsWith("-gh")) {
			// do nothing
		} else if (graphHopperFolder.endsWith(".osm")
				|| graphHopperFolder.endsWith(".xml")) {
			throw new IllegalArgumentException(
					"GraphHopperLocation cannot be the OSM file. Instead you need to use importOrLoad");
		} else if (!graphHopperFolder.contains(".")) {
			if (new File(graphHopperFolder + "-gh").exists())
				graphHopperFolder += "-gh";
		} else {
			File compressed = new File(graphHopperFolder + ".ghz");
			if (compressed.exists() && !compressed.isDirectory()) {
				try {
					new Unzipper().unzip(compressed.getAbsolutePath(),
							graphHopperFolder, false);
				} catch (IOException ex) {
					throw new RuntimeException("Couldn't extract file "
							+ compressed.getAbsolutePath() + " to "
							+ graphHopperFolder, ex);
				}
			}
		}

		GtfsStorage gtfsStorage = createGtfsStorage();

		GHDirectory directory = createGHDirectory(graphHopperFolder);
		GraphHopperStorage graphHopperStorage = createOrLoad(directory,
				encodingManager, gtfsStorage, createWalkNetwork,
				Collections.singleton(gtfsFile), Collections.emptyList());
		LocationIndex locationIndex = createOrLoadIndex(directory,
				graphHopperStorage);

		return new RoadsToGraphHopperGtfs(encodingManager,
				createTranslationMap(), graphHopperStorage, locationIndex,
				gtfsStorage);
	}

	public static GtfsStorage createGtfsStorage() {
		return new GtfsStorage();
	}

	public static GHDirectory createGHDirectory(String graphHopperFolder) {
		return new GHDirectory(graphHopperFolder, DAType.RAM_STORE);
	}

	public static TranslationMap createTranslationMap() {
		return new TranslationMap().doImport();
	}

	public static EncodingManager createEncodingManager() {
		return new EncodingManager(Arrays.asList(new PtFlagEncoder()), 8);
	}

	public static GraphHopperStorage createOrLoad(GHDirectory directory,
			EncodingManager encodingManager, GtfsStorage gtfsStorage,
			boolean createWalkNetwork, Collection<String> gtfsFiles,
			Collection<String> osmFiles) {
		GraphHopperStorage graphHopperStorage = new GraphHopperStorage(
				directory, encodingManager, false, gtfsStorage);
		if (!new File(directory.getLocation()).exists()) {
			graphHopperStorage.create(1000);
			for (String osmFile : osmFiles) {
				OSMReader osmReader = new OSMReader(graphHopperStorage);
				osmReader.setEncodingManager(encodingManager);
				osmReader.setFile(new File(osmFile));
				osmReader.setDontCreateStorage(true);
				try {
					osmReader.readGraph();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			List<GTFSFeed> feeds = gtfsFiles
					.parallelStream()
					.map(filename -> GTFSFeed.fromFile(new File(filename)
							.getPath())).collect(Collectors.toList());
			if (createWalkNetwork) {
				FakeWalkNetworkBuilder.buildWalkNetwork(feeds,
						graphHopperStorage,
						(PtFlagEncoder) encodingManager.getEncoder("pt"),
						Helper.DIST_EARTH);
			}
			LocationIndex locationIndex;
			if (graphHopperStorage.getNodes() > 0) {
				locationIndex = new LocationIndexTree(graphHopperStorage,
						new RAMDirectory()).prepareIndex();
			} else {
				locationIndex = new EmptyLocationIndex();
			}
			for (GTFSFeed feed : feeds) {
				new GtfsReader(feed, graphHopperStorage, locationIndex)
						.readGraph();
			}
			graphHopperStorage.flush();
		} else {
			graphHopperStorage.loadExisting();
		}
		return graphHopperStorage;
	}

	public static LocationIndex createOrLoadIndex(GHDirectory directory,
			GraphHopperStorage graphHopperStorage) {
		LocationIndex locationIndex = new LocationIndexTree(graphHopperStorage,
				directory);
		if (!locationIndex.loadExisting()) {
			locationIndex.prepareIndex();
		}
		return locationIndex;
	}

	public boolean load(String graphHopperFolder) {
		throw new IllegalStateException(
				"We are always loaded, or we wouldn't exist.");
	}

	public Roads roadsFrom(double lat, double lon) {
		GHRequest request = new GHRequest(lat, lon, lat, lon);
		final int maxVisitedNodesForRequest = request.getHints().getInt(
				Parameters.Routing.MAX_VISITED_NODES, Integer.MAX_VALUE);
		final long requestedTimeOfDay = request.getHints().getInt(
				EARLIEST_DEPARTURE_TIME_HINT, 0)
				% (24 * 60 * 60);
		final long requestedDay = request.getHints().getInt(
				EARLIEST_DEPARTURE_TIME_HINT, 0)
				/ (24 * 60 * 60);
		final long initialTime = requestedTimeOfDay + requestedDay
				* (24 * 60 * 60);
		final long rangeQueryEndTime = request.getHints().getLong(
				RANGE_QUERY_END_TIME, initialTime);
		final boolean arriveBy = request.getHints().getBool(ARRIVE_BY, false);

		if (graphHopperStorage == null)
			throw new IllegalStateException(
					"Do a successful call to load or importOrLoad before routing");

		if (graphHopperStorage.isClosed())
			throw new IllegalStateException(
					"You need to create a new GraphHopper instance as it is already closed");

		PtFlagEncoder encoder = (PtFlagEncoder) encodingManager
				.getEncoder("pt");

		if (request.getPoints().size() != 2) {
			throw new IllegalArgumentException(
					"Exactly 2 points have to be specified, but was:"
							+ request.getPoints().size());
		}

		final GHPoint enter = request.getPoints().get(0);
		final GHPoint exit = request.getPoints().get(1);

		Locale locale = request.getLocale();

		EdgeFilter enterFilter = new EverythingButPt(encoder);
		EdgeFilter exitFilter = new EverythingButPt(encoder);

		List<QueryResult> queryResults = new ArrayList<>();

		QueryResult source = locationIndex.findClosest(enter.lat, enter.lon,
				enterFilter);
		if (!source.isValid()) {
			return new Roads(Collections.emptySet());
		}
		queryResults.add(source);

		QueryResult dest = locationIndex.findClosest(exit.lat, exit.lon,
				exitFilter);
		if (!dest.isValid()) {
			return new Roads(Collections.emptySet());
		}
		queryResults.add(dest);

		int startNode;
		int destNode;
		if (arriveBy) {
			startNode = dest.getClosestNode();
			destNode = source.getClosestNode();
		} else {
			startNode = source.getClosestNode();
			destNode = dest.getClosestNode();
		}

		ArrayList<Integer> toNodes = new ArrayList<>();
		toNodes.add(destNode);

		QueryGraph queryGraph = new QueryGraph(graphHopperStorage);
		queryGraph.lookup(queryResults);

		PtTravelTimeWeighting weighting;
		if (arriveBy) {
			weighting = new PtTravelTimeWeighting(encoder).reverse();
		} else {
			weighting = new PtTravelTimeWeighting(encoder);
		}

		GraphExplorer explorer;
		if (arriveBy) {
			explorer = new GraphExplorer(
					graphHopperStorage.createEdgeExplorer(new DefaultEdgeFilter(
							encoder, true, false)), encoder, gtfsStorage, true);
		} else {
			explorer = new GraphExplorer(
					graphHopperStorage.createEdgeExplorer(new DefaultEdgeFilter(
							encoder, false, true)), encoder, gtfsStorage, false);
		}

		MultiCriteriaLabelsSetting router;
		if (arriveBy) {
			router = new MultiCriteriaLabelsSetting(graphHopperStorage,
					weighting, maxVisitedNodesForRequest, explorer, true);
		} else {
			router = new MultiCriteriaLabelsSetting(graphHopperStorage,
					weighting, maxVisitedNodesForRequest, explorer, false);
		}

		Collection<Label> solutions = router.calcLabels(startNode, initialTime,
				rangeQueryEndTime);

		final RoadBuilder roadsBuilder = new RoadBuilder(graphHopperStorage);

		for (Label label : solutions) {
			roadsBuilder.addLabel(label);
		}
		return roadsBuilder.buildRoads();

		/*
		 * Map<Integer, AtomicInteger> cnt = new HashMap<>();
		 * 
		 * Map<PointList, AtomicInteger> cnt1 = new HashMap<>();
		 * 
		 * int paths = solutions.size();
		 * 
		 * for (Label solution : solutions) {
		 * 
		 * // List<EdgeIteratorState> edges = new ArrayList<>(); // if
		 * (arriveBy) { // reverseEdges(solution, graphHopperStorage).forEach(
		 * // edge -> edges.add(edge.detach(false))); // } else { //
		 * reverseEdges(solution, graphHopperStorage).forEach( // edge ->
		 * edges.add(edge.detach(true))); // Collections.reverse(edges); // } if
		 * (solution.parent != null) { cnt.computeIfAbsent(solution.parent.edge,
		 * edge -> new AtomicInteger(0)).incrementAndGet(); } if (solution.edge
		 * != -1) { cnt.computeIfAbsent(solution.edge, edge -> new
		 * AtomicInteger(0)) .incrementAndGet();
		 * 
		 * EdgeIteratorState edgeIteratorState = graphHopperStorage
		 * .getEdgeIteratorState(solution.edge, solution.adjNode);
		 * 
		 * PointList fetchWayGeometry = edgeIteratorState .fetchWayGeometry(1);
		 * 
		 * int baseNode = edgeIteratorState.getBaseNode(); int adjNode =
		 * edgeIteratorState.getAdjNode();
		 * 
		 * PointList coords = new PointList(2, false); double lat0 =
		 * graphHopperStorage.getNodeAccess().getLat( baseNode); double lon0 =
		 * graphHopperStorage.getNodeAccess().getLon( baseNode);
		 * coords.add(lat0, lon0); double lat1 =
		 * graphHopperStorage.getNodeAccess() .getLat(adjNode); double lon1 =
		 * graphHopperStorage.getNodeAccess() .getLon(adjNode); coords.add(lat1,
		 * lon1);
		 * 
		 * if (Double.doubleToLongBits(lat0) != Double .doubleToLongBits(lat1)
		 * || Double.doubleToLongBits(lon0) != Double .doubleToLongBits(lon1)) {
		 * 
		 * if (cnt1.containsKey(coords)) { cnt1.get(coords).incrementAndGet(); }
		 * else { cnt1.put(coords, new AtomicInteger(1)); } } } }
		 * 
		 * final List<Entry<PointList, AtomicInteger>> results = new
		 * ArrayList<>( cnt1.entrySet()); Collections.sort( results,
		 * Comparator.<Entry<PointList, AtomicInteger>> comparingInt( entry ->
		 * entry.getValue().get()).reversed());
		 * 
		 * return solutions;
		 */
	}

}
