package net.osmand.data.preparation;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.osmand.data.TransportRoute;
import net.osmand.data.TransportStop;
import net.osmand.osm.edit.Entity;
import net.osmand.osm.edit.EntityParser;
import net.osmand.osm.edit.Node;
import net.osmand.osm.edit.OsmTransportRoute;
import net.osmand.osm.edit.Relation;
import net.osmand.osm.edit.Way;
import net.osmand.osm.edit.Entity.EntityId;
import net.osmand.osm.edit.OSMSettings.OSMTagKey;
import net.osmand.util.MapUtils;
import net.sf.junidecode.Junidecode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import rtree.Element;
import rtree.IllegalValueException;
import rtree.LeafElement;
import rtree.NonLeafElement;
import rtree.RTree;
import rtree.RTreeException;
import rtree.RTreeInsertException;
import rtree.Rect;

public class IndexTransportCreator extends AbstractIndexPartCreator {
	
	private static final Log log = LogFactory.getLog(IndexTransportCreator.class);

	private Set<Long> visitedStops = new HashSet<Long>();
	private PreparedStatement transRouteStat;
	private PreparedStatement transRouteStopsStat;
	private PreparedStatement transStopsStat;
	private RTree transportStopsTree;
	private Map<Long, Relation> masterRoutes = new HashMap<Long, Relation>();
	// Note: in future when we need more information from stop_area relation, it is better to memorize relations itself
	// now we need only specific names of stops and platforms
	private Map<EntityId, Relation> stopAreas = new HashMap<EntityId, Relation>();

	
	private static Set<String> acceptedRoutes = new HashSet<String>();
	static {
		acceptedRoutes.add("bus"); //$NON-NLS-1$
		acceptedRoutes.add("trolleybus"); //$NON-NLS-1$
		acceptedRoutes.add("share_taxi"); //$NON-NLS-1$

		acceptedRoutes.add("subway"); //$NON-NLS-1$
		acceptedRoutes.add("train"); //$NON-NLS-1$

		acceptedRoutes.add("tram"); //$NON-NLS-1$

		acceptedRoutes.add("ferry"); //$NON-NLS-1$
	}

	public IndexTransportCreator(){
	}
	
	
	public void createRTreeFile(String rtreeTransportStopFile) throws RTreeException{
		transportStopsTree = new RTree(rtreeTransportStopFile);
	}
	
	public void writeBinaryTransportTree(rtree.Node parent, RTree r, BinaryMapIndexWriter writer, 
			PreparedStatement selectTransportStop, PreparedStatement selectTransportRouteStop, 
			Map<Long, Long> transportRoutes, Map<String, Integer> stringTable) throws IOException, RTreeException, SQLException {
		Element[] e = parent.getAllElements();
		List<Long> routes = null;
		for (int i = 0; i < parent.getTotalElements(); i++) {
			Rect re = e[i].getRect();
			if (e[i].getElementType() == rtree.Node.LEAF_NODE) {
				long id = e[i].getPtr();
				selectTransportStop.setLong(1, id);
				selectTransportRouteStop.setLong(1, id);
				ResultSet rs = selectTransportStop.executeQuery();
				if (rs.next()) {
					int x24 = (int) MapUtils.getTileNumberX(24, rs.getDouble(3));
					int y24 = (int) MapUtils.getTileNumberY(24, rs.getDouble(2));
					String name = rs.getString(4);
					String nameEn = rs.getString(5);
					if (nameEn != null && nameEn.equals(Junidecode.unidecode(name))) {
						nameEn = null;
					}
					ResultSet rset = selectTransportRouteStop.executeQuery();
					if (routes == null) {
						routes = new ArrayList<Long>();
					} else {
						routes.clear();
					}
					while (rset.next()) {
						Long route = transportRoutes.get(rset.getLong(1));
						if (route == null) {
							log.error("Something goes wrong with transport route id = " + rset.getLong(1)); //$NON-NLS-1$
						} else {
							routes.add(route);
						}
					}
					rset.close();
					writer.writeTransportStop(id, x24, y24, name, nameEn, stringTable, routes);
				} else {
					log.error("Something goes wrong with transport id = " + id); //$NON-NLS-1$
				}
			} else {
				long ptr = e[i].getPtr();
				rtree.Node ns = r.getReadNode(ptr);

				writer.startTransportTreeElement(re.getMinX(), re.getMaxX(), re.getMinY(), re.getMaxY());
				writeBinaryTransportTree(ns, r, writer, selectTransportStop, selectTransportRouteStop, transportRoutes, stringTable);
				writer.endWriteTransportTreeElement();
			}
		}
	}
	

	public void packRTree(String rtreeTransportStopsFileName, String rtreeTransportStopsPackFileName) throws IOException {
		transportStopsTree = packRtreeFile(transportStopsTree, rtreeTransportStopsFileName, rtreeTransportStopsPackFileName);
	}
	
	public void indexRelations(Relation e, OsmDbAccessorContext ctx) throws SQLException {
		if (e.getTag(OSMTagKey.ROUTE_MASTER) != null) {
			ctx.loadEntityRelation(e);
			for (Entry<Entity, String> child : ((Relation) e).getMemberEntities().entrySet()) {
				Entity entity = child.getKey();
				masterRoutes.put(entity.getId(), (Relation) e);
			}
		}
		if ("stop_area".equals(e.getTag(OSMTagKey.PUBLIC_TRANSPORT))) {
			// save stop area relation members for future processing
			String name = e.getTag(OSMTagKey.NAME);
			if (name == null) return;

			ctx.loadEntityRelation(e);
			for (Entry<Entity, String> entry : e.getMemberEntities().entrySet()) {
				String role = entry.getValue();
				if ("platform".equals(role) || "stop".equals(role)) {
					if (entry.getKey().getTag(OSMTagKey.NAME) == null) {
						stopAreas.put(EntityId.valueOf(entry.getKey()), e);
					}
				}
			}
		}
	}
	
	public void visitEntityMainStep(Entity e, OsmDbAccessorContext ctx) throws SQLException {
		if (e instanceof Relation && e.getTag(OSMTagKey.ROUTE) != null) {
			ctx.loadEntityRelation((Relation) e);
			TransportRoute route = indexTransportRoute((Relation) e);
			if (route != null) {
				insertTransportIntoIndex(route);
			}
		}
	}
	
	public void createDatabaseStructure(Connection conn, DBDialect dialect, String rtreeStopsFileName) throws SQLException, IOException{
		Statement stat = conn.createStatement();
		
        stat.executeUpdate("create table transport_route (id bigint primary key, type varchar(1024), operator varchar(1024)," +
        		"ref varchar(1024), name varchar(1024), name_en varchar(1024), dist int)");
        stat.executeUpdate("create index transport_route_id on transport_route (id)");
        
        stat.executeUpdate("create table transport_route_stop (stop bigint, route bigint, ord int, direction smallint, primary key (route, ord, direction))");
        stat.executeUpdate("create index transport_route_stop_stop on transport_route_stop (stop)");
        stat.executeUpdate("create index transport_route_stop_route on transport_route_stop (route)");
        
        stat.executeUpdate("create table transport_stop (id bigint primary key, latitude double, longitude double, name varchar(1024), name_en varchar(1024))");
        stat.executeUpdate("create index transport_stop_id on transport_stop (id)");
        stat.executeUpdate("create index transport_stop_location on transport_stop (latitude, longitude)");
        
//        if(dialect == DBDialect.SQLITE){
//        	stat.execute("PRAGMA user_version = " + IndexConstants.TRANSPORT_TABLE_VERSION); //$NON-NLS-1$
//        }
        stat.close();
        
        try {
			File file = new File(rtreeStopsFileName);
			if (file.exists()) {
				file.delete();
			}
			transportStopsTree = new RTree(file.getAbsolutePath());
		} catch (RTreeException e) {
			throw new IOException(e);
		}
		transRouteStat = createStatementTransportRouteInsert(conn);
		transRouteStopsStat = createStatementTransportRouteStopInsert(conn);
		transStopsStat = createStatementTransportStopInsert(conn);
		pStatements.put(transRouteStat, 0);
		pStatements.put(transRouteStopsStat, 0);
		pStatements.put(transStopsStat, 0);
	}
	
	
	private void insertTransportIntoIndex(TransportRoute route) throws SQLException {
		transRouteStat.setLong(1, route.getId());
		transRouteStat.setString(2, route.getType());
		transRouteStat.setString(3, route.getOperator());
		transRouteStat.setString(4, route.getRef());
		transRouteStat.setString(5, route.getName());
		transRouteStat.setString(6, route.getEnName(false));
		transRouteStat.setInt(7, route.getAvgBothDistance());
		addBatch(transRouteStat);
		
		writeRouteStops(route, route.getForwardStops(), true);
		writeRouteStops(route, route.getBackwardStops(), false);
		
	}
	
	private PreparedStatement createStatementTransportStopInsert(Connection conn) throws SQLException{
        return conn.prepareStatement("insert into transport_stop(id, latitude, longitude, name, name_en) values(?, ?, ?, ?, ?)");
	}
	private PreparedStatement createStatementTransportRouteStopInsert(Connection conn) throws SQLException{
        return conn.prepareStatement("insert into transport_route_stop(route, stop, direction, ord) values(?, ?, ?, ?)");
	}
	
	private void writeRouteStops(TransportRoute r, List<TransportStop> stops, boolean direction) throws SQLException {
		int i = 0;
		for(TransportStop s : stops){
			if (!visitedStops.contains(s.getId())) {
				transStopsStat.setLong(1, s.getId());
				transStopsStat.setDouble(2, s.getLocation().getLatitude());
				transStopsStat.setDouble(3, s.getLocation().getLongitude());
				transStopsStat.setString(4, s.getName());
				transStopsStat.setString(5, s.getEnName(false));
				int x = (int) MapUtils.getTileNumberX(24, s.getLocation().getLongitude());
				int y = (int) MapUtils.getTileNumberY(24, s.getLocation().getLatitude());
				addBatch(transStopsStat);
				try {
					transportStopsTree.insert(new LeafElement(new Rect(x, y, x, y), s.getId()));
				} catch (RTreeInsertException e) {
					throw new IllegalArgumentException(e);
				} catch (IllegalValueException e) {
					throw new IllegalArgumentException(e);
				}
				visitedStops.add(s.getId());
			}
			transRouteStopsStat.setLong(1, r.getId());
			transRouteStopsStat.setLong(2, s.getId());
			transRouteStopsStat.setInt(3, direction ? 1 : 0);
			transRouteStopsStat.setInt(4, i++);
			addBatch(transRouteStopsStat);
		}
	}
	
	private PreparedStatement createStatementTransportRouteInsert(Connection conn) throws SQLException{
        return conn.prepareStatement("insert into transport_route(id, type, operator, ref, name, name_en, dist) values(?, ?, ?, ?, ?, ?, ?)");
	}
	
	
	public void writeBinaryTransportIndex(BinaryMapIndexWriter writer, String regionName,
			Connection mapConnection) throws IOException, SQLException {
		try {
			closePreparedStatements(transRouteStat, transRouteStopsStat, transStopsStat);
			mapConnection.commit();
			transportStopsTree.flush();
			
			visitedStops = null; // allow gc to collect it
			PreparedStatement selectTransportRouteData = mapConnection.prepareStatement(
					"SELECT id, dist, name, name_en, ref, operator, type FROM transport_route"); //$NON-NLS-1$
			PreparedStatement selectTransportData = mapConnection.prepareStatement("SELECT S.stop, S.direction," + //$NON-NLS-1$
					"  A.latitude,  A.longitude, A.name, A.name_en " + //$NON-NLS-1$
					"FROM transport_route_stop S INNER JOIN transport_stop A ON A.id = S.stop WHERE S.route = ? ORDER BY S.ord asc"); //$NON-NLS-1$

			writer.startWriteTransportIndex(regionName);

			writer.startWriteTransportRoutes();

			// expect that memory would be enough
			Map<String, Integer> stringTable = createStringTableForTransport();
			Map<Long, Long> transportRoutes = new LinkedHashMap<Long, Long>();

			ResultSet rs = selectTransportRouteData.executeQuery();
			List<TransportStop> directStops = new ArrayList<TransportStop>();
			List<TransportStop> reverseStops = new ArrayList<TransportStop>();
			while (rs.next()) {

				long idRoute = rs.getLong(1);
				int dist = rs.getInt(2);
				String routeName = rs.getString(3);
				String routeEnName = rs.getString(4);
				if (routeEnName != null && routeEnName.equals(Junidecode.unidecode(routeName))) {
					routeEnName = null;
				}
				String ref = rs.getString(5);
				String operator = rs.getString(6);
				String type = rs.getString(7);

				selectTransportData.setLong(1, idRoute);
				ResultSet rset = selectTransportData.executeQuery();
				reverseStops.clear();
				directStops.clear();
				while (rset.next()) {
					boolean dir = rset.getInt(2) != 0;
					long idStop = rset.getInt(1);
					String stopName = rset.getString(5);
					String stopEnName = rset.getString(6);
					if (stopEnName != null && stopEnName.equals(Junidecode.unidecode(stopName))) {
						stopEnName = null;
					}
					TransportStop st = new TransportStop();
					st.setId(idStop);
					st.setName(stopName);
					st.setLocation(rset.getDouble(3), rset.getDouble(4));
					if (stopEnName != null) {
						st.setEnName(stopEnName);
					}
					if (dir) {
						directStops.add(st);
					} else {
						reverseStops.add(st);
					}
				}
				writer.writeTransportRoute(idRoute, routeName, routeEnName, ref, operator, type, dist, directStops, reverseStops,
						stringTable, transportRoutes);
			}
			rs.close();
			selectTransportRouteData.close();
			selectTransportData.close();
			writer.endWriteTransportRoutes();

			PreparedStatement selectTransportStop = mapConnection.prepareStatement(
					"SELECT A.id,  A.latitude,  A.longitude, A.name, A.name_en FROM transport_stop A where A.id = ?"); //$NON-NLS-1$
			PreparedStatement selectTransportRouteStop = mapConnection.prepareStatement(
					"SELECT DISTINCT S.route FROM transport_route_stop S join transport_route R  on R.id = S.route WHERE S.stop = ? ORDER BY R.type, R.ref "); //$NON-NLS-1$
			long rootIndex = transportStopsTree.getFileHdr().getRootIndex();
			rtree.Node root = transportStopsTree.getReadNode(rootIndex);
			Rect rootBounds = calcBounds(root);
			if (rootBounds != null) {
				writer.startTransportTreeElement(rootBounds.getMinX(), rootBounds.getMaxX(), rootBounds.getMinY(), rootBounds.getMaxY());
				writeBinaryTransportTree(root, transportStopsTree, writer, selectTransportStop, selectTransportRouteStop, 
						transportRoutes, stringTable);
				writer.endWriteTransportTreeElement();
			}
			selectTransportStop.close();
			selectTransportRouteStop.close();

			writer.writeTransportStringTable(stringTable);

			writer.endWriteTransportIndex();
			writer.flush();
		} catch (RTreeException e) {
			throw new IllegalStateException(e);
		}
	}	
	private Rect calcBounds(rtree.Node n) {
		Rect r = null;
		Element[] e = n.getAllElements();
		for (int i = 0; i < n.getTotalElements(); i++) {
			Rect re = e[i].getRect();
			if (r == null) {
				try {
					r = new Rect(re.getMinX(), re.getMinY(), re.getMaxX(), re.getMaxY());
				} catch (IllegalValueException ex) {
				}
			} else {
				r.expandToInclude(re);
			}
		}
		return r;
	}
	
	private int registerString(Map<String, Integer> stringTable, String s) {
		if (stringTable.containsKey(s)) {
			return stringTable.get(s);
		}
		int size = stringTable.size();
		stringTable.put(s, size);
		return size;
	}

	private Map<String, Integer> createStringTableForTransport() {
		Map<String, Integer> stringTable = new LinkedHashMap<String, Integer>();
		registerString(stringTable, "bus"); //$NON-NLS-1$
		registerString(stringTable, "trolleybus"); //$NON-NLS-1$
		registerString(stringTable, "subway"); //$NON-NLS-1$
		registerString(stringTable, "tram"); //$NON-NLS-1$
		registerString(stringTable, "share_taxi"); //$NON-NLS-1$
		registerString(stringTable, "taxi"); //$NON-NLS-1$
		registerString(stringTable, "train"); //$NON-NLS-1$
		registerString(stringTable, "ferry"); //$NON-NLS-1$
		return stringTable;
	}

	
	public void commitAndCloseFiles(String rtreeStopsFileName, String rtreeStopsPackFileName, boolean deleteDatabaseIndexes) throws IOException, SQLException {
		// delete transport rtree files
		if (transportStopsTree != null) {
			transportStopsTree.getFileHdr().getFile().close();
			File f = new File(rtreeStopsFileName);
			if (f.exists() && deleteDatabaseIndexes) {
				f.delete();
			}
			f = new File(rtreeStopsPackFileName);
			if (f.exists() && deleteDatabaseIndexes) {
				f.delete();
			}
		}
		closeAllPreparedStatements();
	}
	

	
	
	private TransportRoute indexTransportRoute(Relation rel) {
		String ref = rel.getTag(OSMTagKey.REF);
		String route = rel.getTag(OSMTagKey.ROUTE);
		String operator = rel.getTag(OSMTagKey.OPERATOR);
		
		Relation master = masterRoutes.get(rel.getId());
		if (master != null) {
			if (ref == null)
				ref = master.getTag(OSMTagKey.REF);
			if (route == null)
				route = master.getTag(OSMTagKey.ROUTE);
			if (operator == null)
				operator = master.getTag(OSMTagKey.OPERATOR);
		}
		
		if (route == null || ref == null) {
			return null;
		}
		if (!acceptedRoutes.contains(route)) {
			return null;
		}
		OsmTransportRoute r = EntityParser.parserRoute(rel, ref);
		r.setOperator(operator);
		r.setType(route);

		if (operator != null) {
			route = operator + " : " + route; //$NON-NLS-1$
		}

		if (!processNewTransportRelation(rel, r)) { // try new transport relations first
			if (!processOldTransportRelation(rel, r)) { // old relation style otherwise
				return null;
			}
		}

		return r;
	}


	private Pattern platforms = Pattern.compile("^(stop|platform)_(entry|exit)_only$");
	private Matcher stopPlatformMatcher = platforms.matcher("");

	private boolean processNewTransportRelation(Relation rel, OsmTransportRoute r) {
		// first, verify we can accept this relation as new transport relation
		// accepted roles restricted to: <empty>, stop, platform, ^(stop|platform)_(entry|exit)_only$

		for (Entry<Entity, String> entry : rel.getMemberEntities().entrySet()) {
			String role = entry.getValue();
			if (role.isEmpty()) continue; // accepted roles
			if ("stop".equals(role)) continue;
			if ("platform".equals(role)) continue;

			stopPlatformMatcher.reset(role);
			if (stopPlatformMatcher.matches()) continue;

			return false; // there is wrong role in the relation, exit
		}

		List<Entity> platforms = new ArrayList<Entity>();
		List<Entity> stops = new ArrayList<Entity>();
		for (Entry<Entity, String> entry : rel.getMemberEntities().entrySet()) {
			String role = entry.getValue();
			if(entry.getKey().getLatLon() == null) {
				continue;
			}
			if (role.startsWith("platform")) {
				platforms.add(entry.getKey());
			} else if (role.startsWith("stop")) {
				stops.add(entry.getKey());
			} else {
				if (entry.getKey() instanceof Way) {
					r.addWay((Way) entry.getKey());
				}
			}
		}

		Map<Entity, String> replacement = new HashMap<Entity, String>();
		List<Entity> merged = mergePlatformsStops(platforms, stops, replacement);

		if (merged.isEmpty())
			return false; // nothing to get from this relation - there is no stop

		for (Entity s : merged) {
			TransportStop stop = EntityParser.parseTransportStop(s);

			// name replacement (platform<->stop)
			if (replacement.containsKey(s)) {
				stop.setName(replacement.get(s));
			} else
			// refill empty name with name from stop_area relation if there was such
			// verify name tag, not stop.getName because it may contain unnecessary refs, etc
			if (s.getTag(OSMTagKey.NAME) == null && stopAreas.containsKey(EntityId.valueOf(s)))
				stop.setName(stopAreas.get(EntityId.valueOf(s)).getTag(OSMTagKey.NAME));

			r.getForwardStops().add(stop);
		}

		return true;
	}

	private List<Entity> mergePlatformsStops(List<Entity> platforms, List<Entity> stops, Map<Entity, String> nameReplacement) {

		// simple first - use one if other is empty
		if (platforms.isEmpty())
			return stops;
		if (stops.isEmpty())
			return platforms;

		// walk through bigger array (platforms or stops), and verify names from the second:

		List<Entity> first;
		List<Entity> second;
		List<Entity> merge = new ArrayList<Entity>();
		if (platforms.size() > stops.size()) {
			first = platforms;
			second = stops;
		} else {
			first = stops;
			second = platforms;
		}

		// find stops and platforms that are part of one station - it could be stopArea or distance compare

		Map<Entity, Entity> fullStops = new HashMap<Entity, Entity>();

		for (Entity a : first) {
			Entity bMin = null;
			Relation aStopArea = stopAreas.get(a);
			if(a.getLatLon() == null){
				continue;
			}
			double distance = 1.0e10;
			for (Entity b : second) {
				if (b.getLatLon() == null) {
					continue;
				}
				double d = MapUtils.getDistance(a.getLatLon(), b.getLatLon());
				if (d < distance) {
					distance = d;
					bMin = b;
				}
				if (aStopArea != null && aStopArea == stopAreas.get(b)) {
					// the best match - both are in one stop_area relation
					bMin = b;
					distance = 0;
					break;
				}
			}
			if (bMin != null && distance < 300) {
				fullStops.put(a, bMin);
			}
		}

		// walk through bigger array and fill nameReplacement map with correct names
		// prefer platforms when both variants exist

		ListIterator<Entity> i1 = first.listIterator();
		while (i1.hasNext()) {
			Entity a = i1.next();
			if (fullStops.containsKey(a)) {
				Entity b = fullStops.get(a);

				// check which element satisfies us better (a or b) - which is platform
				boolean useA = true;
				if (!"platform".equals(a.getTag(OSMTagKey.PUBLIC_TRANSPORT))) {
					if("platform".equals(b.getTag(OSMTagKey.PUBLIC_TRANSPORT)) || platforms.contains(b))
						useA = false;
				}

				if (useA) {
					merge.add(a);
				} else {
					merge.add(b);
				}

				// if a does not have name, but b has - add a nameReplacement
				Entity platform = useA?a:b;
				Entity stop = useA?b:a;
				if (stop.getTag(OSMTagKey.NAME) != null) {
					nameReplacement.put(platform, stop.getTag(OSMTagKey.NAME));
				}
			} else {
				merge.add(a);
			}
		}

		return merge;
	}


	private boolean processOldTransportRelation(Relation rel, OsmTransportRoute r) {
		final Map<TransportStop, Integer> forwardStops = new LinkedHashMap<TransportStop, Integer>();
		final Map<TransportStop, Integer> backwardStops = new LinkedHashMap<TransportStop, Integer>();
		int currentStop = 0;
		int forwardStop = 0;
		int backwardStop = 0;
		for (Entry<Entity, String> e : rel.getMemberEntities().entrySet()) {
			if (e.getValue().contains("stop") || e.getValue().contains("platform")) { //$NON-NLS-1$
				if (e.getKey() instanceof Node) {
					TransportStop stop = EntityParser.parseTransportStop(e.getKey());
					// add stop name if there was no name on the point, but was name on the corresponding stop_area relation
					if (e.getKey().getTag(OSMTagKey.NAME) == null && stopAreas.containsKey(EntityId.valueOf(e.getKey())))
						stop.setName(stopAreas.get(EntityId.valueOf(e.getKey())).getTag(OSMTagKey.NAME));
					boolean forward = e.getValue().contains("forward"); //$NON-NLS-1$
					boolean backward = e.getValue().contains("backward"); //$NON-NLS-1$
					currentStop++;
					if (forward || !backward) {
						forwardStop++;
					}
					if (backward) {
						backwardStop++;
					}
					boolean common = !forward && !backward;
					int index = -1;
					int i = e.getValue().length() - 1;
					int accum = 1;
					while (i >= 0 && Character.isDigit(e.getValue().charAt(i))) {
						if (index < 0) {
							index = 0;
						}
						index = accum * Character.getNumericValue(e.getValue().charAt(i)) + index;
						accum *= 10;
						i--;
					}
					if (index < 0) {
						index = forward ? forwardStop : (backward ? backwardStop : currentStop);
					}
					if (forward || common) {
						forwardStops.put(stop, index);
						r.getForwardStops().add(stop);
					}
					if (backward || common) {
						if (common) {
							// put with negative index
							backwardStops.put(stop, -index);
						} else {
							backwardStops.put(stop, index);
						}

						r.getBackwardStops().add(stop);
					}

				}

			} else if (e.getKey() instanceof Way) {
				r.addWay((Way) e.getKey());
			}
		}
		if (forwardStops.isEmpty() && backwardStops.isEmpty()) {
			return false;
		}
		Collections.sort(r.getForwardStops(), new Comparator<TransportStop>() {
			@Override
			public int compare(TransportStop o1, TransportStop o2) {
				return forwardStops.get(o1) - forwardStops.get(o2);
			}
		});
		// all common stops are with negative index (reeval them)
		for (TransportStop s : new ArrayList<TransportStop>(backwardStops.keySet())) {
			if (backwardStops.get(s) < 0) {
				backwardStops.put(s, backwardStops.size() + backwardStops.get(s) - 1);
			}
		}
		Collections.sort(r.getBackwardStops(), new Comparator<TransportStop>() {
			@Override
			public int compare(TransportStop o1, TransportStop o2) {
				return backwardStops.get(o1) - backwardStops.get(o2);
			}
		});
		return true;
	}
	
}
