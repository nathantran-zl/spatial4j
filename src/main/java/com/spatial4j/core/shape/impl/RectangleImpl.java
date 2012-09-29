/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.spatial4j.core.shape.impl;

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.shape.Point;
import com.spatial4j.core.shape.Rectangle;
import com.spatial4j.core.shape.Shape;
import com.spatial4j.core.shape.SpatialRelation;

/**
 * A simple Rectangle implementation that also supports a longitudinal
 * wrap-around. When minX > maxX, this will assume it is world coordinates that
 * cross the date line using degrees. Immutable & threadsafe.
 */
public class RectangleImpl extends Rectangle {

  private final SpatialContext ctx;
  private double minX;
  private double maxX;
  private double minY;
  private double maxY;

  /** A simple constructor without normalization / validation. */
  public RectangleImpl(double minX, double maxX, double minY, double maxY, SpatialContext ctx) {
    //TODO change to West South East North to be more consistent with OGC?
    this.ctx = ctx;
    reset(minX, maxX, minY, maxY);
  }

  /** A convenience constructor which pulls out the coordinates. */
  public RectangleImpl(Point lowerLeft, Point upperRight, SpatialContext ctx) {
    this(lowerLeft.getX(), upperRight.getX(),
        lowerLeft.getY(), upperRight.getY(), ctx);
  }

  /** Copy constructor. */
  public RectangleImpl(Rectangle r, SpatialContext ctx) {
    this(r.getMinX(), r.getMaxX(), r.getMinY(), r.getMaxY(), ctx);
  }

  @Override
  public void reset(double minX, double maxX, double minY, double maxY) {
    this.minX = minX;
    this.maxX = maxX;
    this.minY = minY;
    this.maxY = maxY;
    assert minY <= maxY;
  }

  @Override
  public boolean hasArea() {
    return maxX != minX && maxY != minY;
  }

  @Override
  public double getArea(SpatialContext ctx) {
    if (ctx == null) {
      return getWidth() * getHeight();
    } else {
      return ctx.getDistanceCalculator().area(this);
    }
  }

  @Override
  public boolean getCrossesDateLine() {
    return (minX > maxX);
  }

  @Override
  public double getHeight() {
    return maxY - minY;
  }

  @Override
  public double getWidth() {
    double w = maxX - minX;
    if (w < 0) {//only true when minX > maxX (WGS84 assumed)
      w += 360;
      assert w >= 0;
    }
    return w;
  }

  @Override
  public double getMaxX() {
    return maxX;
  }

  @Override
  public double getMaxY() {
    return maxY;
  }

  @Override
  public double getMinX() {
    return minX;
  }

  @Override
  public double getMinY() {
    return minY;
  }

  @Override
  public Rectangle getBoundingBox() {
    return this;
  }

  @Override
  public SpatialRelation relate(Shape other) {
    if (other instanceof Point) {
      return relate((Point) other);
    }
    if (other instanceof Rectangle) {
      return relate((Rectangle) other);
    }
    return other.relate(this).transpose();
  }

  public SpatialRelation relate(Point point) {
    if (point.getY() > getMaxY() || point.getY() < getMinY())
      return SpatialRelation.DISJOINT;
    //  all the below logic is rather unfortunate but some dateline cases demand it
    double minX = this.minX;
    double maxX = this.maxX;
    double pX = point.getX();
    if (ctx.isGeo()) {
      //unwrap dateline and normalize +180 to become -180
      double rawWidth = maxX - minX;
      if (rawWidth < 0) {
        maxX = minX + (rawWidth + 360);
      }
      //shift to potentially overlap
      if (pX < minX) {
        pX += 360;
      } else if (pX > maxX) {
        pX -= 360;
      } else {
        return SpatialRelation.CONTAINS;//short-circuit
      }
    }
    if (pX < minX || pX > maxX)
      return SpatialRelation.DISJOINT;
    return SpatialRelation.CONTAINS;
  }

  public SpatialRelation relate(Rectangle rect) {
    SpatialRelation yIntersect = relateYRange(rect.getMinY(), rect.getMaxY());
    if (yIntersect == SpatialRelation.DISJOINT)
      return SpatialRelation.DISJOINT;

    SpatialRelation xIntersect = relateXRange(rect.getMinX(), rect.getMaxX());
    if (xIntersect == SpatialRelation.DISJOINT)
      return SpatialRelation.DISJOINT;

    if (xIntersect == yIntersect)//in agreement
      return xIntersect;

    //if one side is equal, return the other
    if (getMinX() == rect.getMinX() && getMaxX() == rect.getMaxX())
      return yIntersect;
    if (getMinY() == rect.getMinY() && getMaxY() == rect.getMaxY())
      return xIntersect;

    return SpatialRelation.INTERSECTS;
  }

  //TODO might this utility move to SpatialRelation ?
  private static SpatialRelation relateRange(double intMin, double intMax, double extMin, double extMax) {
    if (extMin > intMax || extMax < intMin) {
      return SpatialRelation.DISJOINT;
    }

    if (extMin >= intMin && extMax <= intMax) {
      return SpatialRelation.CONTAINS;
    }

    if (extMin <= intMin && extMax >= intMax) {
      return SpatialRelation.WITHIN;
    }
    return SpatialRelation.INTERSECTS;
  }

  @Override
  public SpatialRelation relateYRange(double extMinY, double extMaxY) {
    return relateRange(minY, maxY, extMinY, extMaxY);
  }

  @Override
  public SpatialRelation relateXRange(double extMinX, double extMaxX) {
    //For ext & this we have local minX and maxX variable pairs. We rotate them so that minX <= maxX
    double minX = this.minX;
    double maxX = this.maxX;
    if (ctx.isGeo()) {
      //unwrap dateline, plus do world-wrap short circuit
      double rawWidth = maxX - minX;
      if (rawWidth == 360)
        return SpatialRelation.CONTAINS;
      if (rawWidth < 0) {
        maxX = minX + (rawWidth + 360);
      }
      double extRawWidth = extMaxX - extMinX;
      if (extRawWidth == 360)
        return SpatialRelation.WITHIN;
      if (extRawWidth < 0) {
        extMaxX = extMinX + (extRawWidth + 360);
      }
      //shift to potentially overlap
      if (maxX < extMinX) {
        minX += 360;
        maxX += 360;
      } else if (extMaxX < minX) {
        extMinX += 360;
        extMaxX += 360;
      }
    }

    return relateRange(minX, maxX, extMinX, extMaxX);
  }

  @Override
  public String toString() {
    return "Rect(minX=" + minX + ",maxX=" + maxX + ",minY=" + minY + ",maxY=" + maxY + ")";
  }

  @Override
  public Point getCenter() {
    final double y = getHeight() / 2 + minY;
    double x = getWidth() / 2 + minX;
    if (minX > maxX)//WGS84
      x = DistanceUtils.normLonDEG(x);//in case falls outside the standard range
    return new PointImpl(x, y, ctx);
  }
}