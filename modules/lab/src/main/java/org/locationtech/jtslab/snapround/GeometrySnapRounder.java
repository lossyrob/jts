/*
 * Copyright (c) 2016 Vivid Solutions.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package org.locationtech.jtslab.snapround;/*

* implement the fundamental operations required to validate a given
* geo-spatial data set to a known topological specification.
*
* Copyright (C) 2001 Vivid Solutions
*
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU Lesser General Public
* License as published by the Free Software Foundation; either
* version 2.1 of the License, or (at your option) any later version.
*
* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this library; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*
* For more information, contact:
*
*     Vivid Solutions
*     Suite #1A
*     2328 Government Street
*     Victoria BC  V8T 5G5
*     Canada
*
*     (250)385-6040
*     www.vividsolutions.com
*/

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateList;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.noding.NodedSegmentString;
import org.locationtech.jts.noding.Noder;
import org.locationtech.jts.noding.snapround.MCIndexSnapRounder;
import org.locationtech.jtslab.geom.util.GeometryEditorEx;

/**
 * Nodes a {@link Geometry} using Snap-Rounding
 * to a given {@link PrecisionModel}.
 * <ul>
 * <li>Point geometries are not handled.  They are skipped if present in the input.
 * <li>Linestrings which collapse to a point due to snapping are removed.
 * <li>Polygonal output may not be valid.  
 * Invalid output is due to the introduction of topology collapses.
 * This should be straightforward to clean using standard heuristics (e.g. buffer(0) ).
 * </ul>
 * The input geometry coordinates are expected to be rounded
 * to the given precision model.
 * This class does not perform that function.
 * <code>GeometryPrecisionReducer</code> may be used to do this.
 */
public class GeometrySnapRounder
{
  private PrecisionModel pm;
  private boolean isLineworkOnly = false;

  /**
   * Creates a new snap-rounder which snap-rounds to a grid specified
   * by the given {@link PrecisionModel}.
   * 
   * @param pm the precision model for the grid to snap-round to
   */
  public GeometrySnapRounder(PrecisionModel pm) {
    this.pm = pm;
  }
  
  public void setLineworkOnly(boolean isLineworkOnly) {
    this.isLineworkOnly = isLineworkOnly;
  }
  
  /**
   * Snap-rounds the given geometry.
   *  
   * 
   * @param geom
   * @return
   */
  public Geometry execute(Geometry geom) {
    
    // TODO: reduce precision of input automatically
    // TODO: add switch to GeometryPrecisionReducer to NOT check & clean invalid polygonal geometry (not needed here)
    // TODO: OR just do precision reduction with custom code here 
    
    List segStrings = extractTaggedSegmentStrings(geom, pm);
    snapRound(segStrings);
    
    if (isLineworkOnly) {
      return toNodedLines(segStrings, geom.getFactory());
    }
    
    Geometry geomSnapped = replaceLines(geom, segStrings);
    Geometry geomClean = ensureValid(geomSnapped);
    return geomClean;
  }

  private Geometry toNodedLines(Collection segStrings, GeometryFactory geomFact) {
    List lines = new ArrayList();
    for (Iterator it = segStrings.iterator(); it.hasNext(); ) {
      NodedSegmentString nss = (NodedSegmentString) it.next();
      // skip collapsed lines
      if (nss.size() < 2)
        continue;
      //Coordinate[] pts = getCoords(nss);
      Coordinate[] pts = nss.getNodeList().getSplitCoordinates();
      
      lines.add(geomFact.createLineString(pts));
    }
    return geomFact.buildGeometry(lines);
  }
  
  private Geometry replaceLines(Geometry geom, List segStrings) {
    Map nodedLinesMap = nodedLinesMap(segStrings);
    GeometryCoordinateReplacer lineReplacer = new GeometryCoordinateReplacer(nodedLinesMap);
    GeometryEditorEx geomEditor = new GeometryEditorEx(lineReplacer);
    Geometry snapped = geomEditor.edit(geom);
    return snapped;
  }

  private void snapRound(List segStrings) {
    //Noder sr = new SimpleSnapRounder(pm);
    Noder sr = new MCIndexSnapRounder(pm);
    sr.computeNodes(segStrings);
  }

  private HashMap nodedLinesMap(Collection segStrings) {
    HashMap ptsMap = new HashMap();
    for (Iterator it = segStrings.iterator(); it.hasNext(); ) {
      NodedSegmentString nss = (NodedSegmentString) it.next();
      // skip collapsed lines
      if (nss.size() < 2)
        continue;
      Coordinate[] pts = nss.getNodeList().getSplitCoordinates();
      ptsMap.put(nss.getData(), pts);
    }
    return ptsMap;
  }
  
  static List extractTaggedSegmentStrings(Geometry geom, final PrecisionModel pm)
  {
    final List segStrings = new ArrayList();
    GeometryComponentFilter filter = new GeometryComponentFilter() {
      public void filter(Geometry geom) {
        // Extract linework for lineal components only
        if (! (geom instanceof LineString) ) return;
        // skip empty lines
        if (geom.getNumPoints() <= 0) return;
        Coordinate[] roundPts = round( ((LineString)geom).getCoordinateSequence(), pm);
        segStrings.add(new NodedSegmentString(roundPts, geom));
      }
    };
    geom.apply(filter);
    return segStrings;
  }
  
  static Coordinate[] round(CoordinateSequence seq, PrecisionModel pm) {
    if (seq.size() == 0) return new Coordinate[0];

    CoordinateList coordList = new CoordinateList();  
    // copy coordinates and reduce
    for (int i = 0; i < seq.size(); i++) {
      Coordinate coord = new Coordinate(
          seq.getOrdinate(i,  Coordinate.X),
          seq.getOrdinate(i,  Coordinate.Y) );
      pm.makePrecise(coord);
      coordList.add(coord, false);
    }
    Coordinate[] coord = coordList.toCoordinateArray();
    
    //TODO: what if seq is too short?
    return coord;
  }
  
  private static Geometry ensureValid(Geometry geom) {
    if (geom.isValid()) return geom;
    return cleanPolygonal(geom);
  }

  private static Geometry cleanPolygonal(Geometry geom) {
    return PolygonCleaner.clean(geom);
  }
}


