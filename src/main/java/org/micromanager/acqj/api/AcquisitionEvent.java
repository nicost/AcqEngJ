///////////////////////////////////////////////////////////////////////////////
// AUTHOR:       Henry Pinkard, henry.pinkard@gmail.com
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
//
package org.micromanager.acqj.api;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import mmcorej.org.json.JSONArray;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.acqj.api.mda.XYStagePosition;
import org.micromanager.acqj.internal.acqengj.AffineTransformUtils;
import org.micromanager.acqj.internal.acqengj.Engine;

/**
 * Information about the acquisition of a single image or a sequence of image
 *
 */
public class AcquisitionEvent {

   enum SpecialFlag {
      AcqusitionFinished,
      AcqusitionSequenceEnd
   };

   public Acquisition acquisition_;

   //For encoded time, z indices (or other generic axes)
   //XY position and channel indices should not be encoded because acq engine
   //will dynamically infer them at runtime
   private HashMap<String, Integer> axisPositions_ = new HashMap<String, Integer>();

   private String channelGroup_, channelConfig_ = null;
   private Double exposure_ = null; //leave null to keep exposaure unchanged

   private Long miniumumStartTime_ms_; //For pausing between time points

   //positions for devices that are generically hardcoded into MMCore
   private Double zPosition_ = null, xPosition_ = null, yPosition_ = null;
   //info for xy positions arranged in a grid
   private Integer gridRow_ = null, gridCol_ = null;
   private Integer overlapX_ = null, overlapY_ = null;
   //TODO: SLM, Galvo, etc

   //Arbitary additional properties
   private TreeSet<ThreeTuple> properties_ = new TreeSet<ThreeTuple>();

   //for hardware sequencing
   private List<AcquisitionEvent> sequence_ = null;
   private boolean xySequenced_ = false, zSequenced_ = false, exposureSequenced_ = false, channelSequenced_ = false;

   //To specify end of acquisition or end of sequence
   private SpecialFlag specialFlag_;

   public AcquisitionEvent(AcquisitionInterface acq) {
      acquisition_ = (Acquisition) acq;
   }

   /**
    * Constructor used for running a list of events in a sequence It should have
    * already been verified that these events are sequencable. This constructor
    * figures out which device types need a sequence and which ones can be left
    * with a single value
    *
    * @param sequence
    */
   public AcquisitionEvent(List<AcquisitionEvent> sequence) {
      sequence_ = new ArrayList<>();
      sequence_.addAll(sequence);
      TreeSet<Double> zPosSet = new TreeSet<Double>();
      HashSet<Double> xPosSet = new HashSet<Double>();
      HashSet<Double> yPosSet = new HashSet<Double>();
      TreeSet<Double> exposureSet = new TreeSet<Double>();
      TreeSet<String> configSet = new TreeSet<String>();
      for (int i = 0; i < sequence_.size(); i++) {
         zPosSet.add(sequence_.get(i).zPosition_);
         xPosSet.add(sequence_.get(i).getXPosition());
         yPosSet.add(sequence_.get(i).getYPosition());
         exposureSet.add(sequence_.get(i).getExposure());
         configSet.add(sequence_.get(i).getChannelConfig());
      }
      exposureSequenced_ = exposureSet.size() > 1;
      channelSequenced_ = configSet.size() > 1;
      xySequenced_ = xPosSet.size() > 1 && yPosSet.size() > 1;
      zSequenced_ = zPosSet.size() > 1;
   }

   public AcquisitionEvent copy() {
      AcquisitionEvent e = new AcquisitionEvent(this.acquisition_);
      e.axisPositions_ = (HashMap<String, Integer>) axisPositions_.clone();
      e.channelConfig_ = channelConfig_;
      e.channelGroup_ = channelConfig_;
      e.zPosition_ = zPosition_;
      e.xPosition_ = xPosition_;
      e.yPosition_ = yPosition_;
      e.gridRow_ = gridRow_;
      e.gridCol_ = gridCol_;
      e.overlapX_ = overlapX_;
      e.overlapY_ = overlapY_;
      e.miniumumStartTime_ms_ = miniumumStartTime_ms_;
      e.properties_ = new TreeSet<ThreeTuple>(this.properties_);
      return e;
   }

   public JSONObject toJSON() {
      try {
         JSONObject json = new JSONObject();
         if (this.isAcquisitionFinishedEvent()) {
            json.put("special", "acquisition-end");
            return json;
         } else if (this.isAcquisitionSequenceEndEvent()) {
            json.put("special", "sequence-end");
            return json;
         }

         //timelpases
         if (miniumumStartTime_ms_ != null) {
            json.put("min_start_time", miniumumStartTime_ms_ / 1000);
         }

         if (hasChannel()) {
            JSONObject channel = new JSONObject();
            channel.put("group", channelGroup_);
            channel.put("config", channelConfig_);
            json.put("channel", channel);
         }

         if (exposure_ != null) {
            json.put("exposure", exposure_);
         }

         //Coordinate indices
         JSONObject axes = new JSONObject();
         for (String axis : axisPositions_.keySet()) {
            axes.put(axis, axisPositions_.get(axis));
         }
         json.put("axes", axes);

         //Things for which a generic device tyoe and functions to operate on
         //it exists in MMCore
         if (zPosition_ != null) {
            json.put("z", zPosition_);
         }
         if (xPosition_ != null) {
            json.put("x", xPosition_);
         }
         if (yPosition_ != null) {
            json.put("y", yPosition_);
         }

         if (gridRow_ != null) {
            json.put("row", gridRow_);
         }
         if (gridCol_ != null) {
            json.put("col", gridCol_);
         }
         if (overlapX_ != null) {
            json.put("overlap_x", overlapX_);
         }
         if (overlapY_ != null) {
            json.put("overlap_y", overlapY_);
         }

         //TODO: SLM, galvo, etc
         //TODO:ability to do API calls (like SLM set image)
         //Arbitrary extra properties
         JSONArray props = new JSONArray();
         for (ThreeTuple t : properties_) {
            JSONArray prop = new JSONArray();
            prop.put(t.dev);
            prop.put(t.prop);
            prop.put(t.val);
            props.put(prop);
         }
         json.put("properties", props);

         return json;
      } catch (JSONException ex) {
         throw new RuntimeException(ex);
      }
   }

   public static AcquisitionEvent fromJSON(JSONObject json, AcquisitionInterface acq) {
      try {
         if (json.has("special")) {
            if (json.getString("special").equals("acquisition-end")) {
               return AcquisitionEvent.createAcquisitionFinishedEvent(acq);
            } else if (json.getString("special").equals("sequence-end")) {
               return AcquisitionEvent.createAcquisitionSequenceEndEvent(acq);
            }
         }

         AcquisitionEvent event = new AcquisitionEvent(acq);

         //convert JSON axes to internal hashmap
         if (json.has("axes")) {
            JSONObject axes = json.getJSONObject("axes");
            axes.keys().forEachRemaining((String axisLabel) -> {
               try {
                  event.axisPositions_.put(axisLabel, axes.getInt(axisLabel));
               } catch (JSONException ex) {
                  throw new RuntimeException(ex);
               }
            });
         }
         //timelpases
         if (json.has("min_start_time")) {
            event.miniumumStartTime_ms_ = (long) (json.getDouble("min_start_time") * 1000);
         }

         //channel name
         if (json.has("channel")) {
            event.channelConfig_ = json.getJSONObject("channel").getString("config");
            event.channelGroup_ = json.getJSONObject("channel").getString("group");
         }
         if (json.has("exposure")) {
            event.exposure_ = json.getDouble("exposure");
         }

         //Things for which a generic device type exists in MMCore
         if (json.has("z")) {
            event.zPosition_ = json.getDouble("z");
         }
         if (json.has("x")) {
            event.xPosition_ = json.getDouble("x");
         }
         if (json.has("y")) {
            event.yPosition_ = json.getDouble("y");
         }
         if (json.has("row")) {
            event.gridRow_ = json.getInt("row");
         }
         if (json.has("col")) {
            event.gridCol_ = json.getInt("col");
         }
         if (json.has("overlap_x")) {
            event.overlapX_ = json.getInt("overlap_x");
         }
         if (json.has("overlap_y")) {
            event.overlapY_ = json.getInt("overlap_y");
         }
         //TODO: SLM, galvo, etc

         //Arbitrary additional properties
         if (json.has("properties")) {
            JSONArray propList = json.getJSONArray("properties");
            for (int i = 0; i < propList.length(); i++) {
               JSONArray trip = propList.getJSONArray(i);
               ThreeTuple t = new ThreeTuple(trip.getString(0), trip.getString(1), trip.getString(2));
               event.properties_.add(t);
            }
         }

         return event;
      } catch (JSONException ex) {
         throw new RuntimeException(ex);
      }
   }

   public List<String[]> getAdditonalProperties() {
      ArrayList<String[]> list = new ArrayList<String[]>();
      for (ThreeTuple t : properties_) {
         list.add(new String[]{t.dev, t.prop, t.val});
      }
      return list;
   }

   public boolean hasChannel() {
      return channelConfig_ != null && channelConfig_ != null;
   }

   public String getChannelConfig() {
      return channelConfig_;
   }

   public String getChannelGroup() {
      return channelGroup_;
   }

   public void setChannelConfig(String config) {
      channelConfig_ = config;
   }

   public void setChannelGroup(String group) {
      channelGroup_ = group;
   }

   public Double getExposure() {
      return exposure_;
   }

   public void setExposure(double exposure) {
      exposure_ = exposure;
   }

   /**
    * Set the minimum start time in ms relative to when the acq started
    *
    * @param l
    */
   public void setMinimumStartTime(Long l) {
      miniumumStartTime_ms_ = l;
   }

   public Set<String> getDefinedAxes() {
      return axisPositions_.keySet();
   }

   public void setAxisPosition(String label, int index) {
      axisPositions_.put(label, index);
   }

   public Integer getAxisPosition(String label) {
      if (!axisPositions_.containsKey(label)) {
         return null;
      }
      return axisPositions_.get(label);
   }

   public void setTimeIndex(int index) {
      setAxisPosition(AcqEngMetadata.TIME_AXIS, index);
   }

   public void setZ(Integer index, Double position) {
      if (index != null) {
         setAxisPosition(AcqEngMetadata.Z_AXIS, index);
      }
      zPosition_ = position;
   }

   public int getTIndex() {
      return getAxisPosition(AcqEngMetadata.TIME_AXIS);
   }

   public Integer getZIndex() {
      return getAxisPosition(AcqEngMetadata.Z_AXIS);
   }

   public static AcquisitionEvent createAcquisitionFinishedEvent(AcquisitionInterface acq) {
      AcquisitionEvent evt = new AcquisitionEvent(acq);
      evt.specialFlag_ = SpecialFlag.AcqusitionFinished;
      return evt;
   }

   public boolean isAcquisitionFinishedEvent() {
      return specialFlag_ == SpecialFlag.AcqusitionFinished;
   }

   public static AcquisitionEvent createAcquisitionSequenceEndEvent(AcquisitionInterface acq) {
      AcquisitionEvent evt = new AcquisitionEvent(acq);
      evt.specialFlag_ = SpecialFlag.AcqusitionSequenceEnd;
      return evt;
   }

   public boolean isAcquisitionSequenceEndEvent() {
      return specialFlag_ == SpecialFlag.AcqusitionSequenceEnd;
   }

   public Double getZPosition() {
      return zPosition_;
   }

   /**
    * get the minimum start timein system time
    *
    * @return
    */
   public Long getMinimumStartTimeAbsolute() {
      if (miniumumStartTime_ms_ == null) {
         return null;
      }
      return acquisition_.getStartTime_ms() + miniumumStartTime_ms_;
   }

   public List<AcquisitionEvent> getSequence() {
      return sequence_;
   }

   public boolean isExposureSequenced() {
      return exposureSequenced_;
   }

   public boolean isChannelSequenced() {
      return channelSequenced_;
   }

   public boolean isXYSequenced() {
      return xySequenced_;
   }

   public boolean isZSequenced() {
      return zSequenced_;
   }

   public Point2D.Double[] getDisplayPositionCorners() {
      if (xPosition_ == null || yPosition_ == null) {
         throw new RuntimeException("xy position undefined");
      }
      int width = (int) Engine.getCore().getImageWidth();
      int height = (int) Engine.getCore().getImageHeight();
      int displayTileWidth = width - (overlapX_ != null ? overlapX_ : 0);
      int displayTileHeight = height - (overlapY_ != null ? overlapY_ : 0);
      Point2D.Double[] displayedTileCorners = new Point2D.Double[4];
      displayedTileCorners[0] = new Point2D.Double();
      displayedTileCorners[1] = new Point2D.Double();
      displayedTileCorners[2] = new Point2D.Double();
      displayedTileCorners[3] = new Point2D.Double();
      //this AT is centered at the stage position, becuase there no global translation relevant to a single stage position
      AffineTransform transform = AffineTransformUtils.getAffineTransform(
              xPosition_, yPosition_);
      transform.transform(new Point2D.Double(-displayTileWidth / 2, -displayTileHeight / 2), displayedTileCorners[0]);
      transform.transform(new Point2D.Double(-displayTileWidth / 2, displayTileHeight / 2), displayedTileCorners[1]);
      transform.transform(new Point2D.Double(displayTileWidth / 2, displayTileHeight / 2), displayedTileCorners[2]);
      transform.transform(new Point2D.Double(displayTileWidth / 2, -displayTileHeight / 2), displayedTileCorners[3]);
      return displayedTileCorners;
   }

   public Double getXPosition() {
      return xPosition_;
   }

   public Double getYPosition() {
      return yPosition_;
   }

   public Integer getGridRow() {
      return gridRow_;
   }

   public Integer getGridCol() {
      return gridCol_;
   }

   public void setX(double x) {
      xPosition_ = x;
   }

   public void setY(double y) {
      yPosition_ = y;
   }

   public void setGridRow(Integer gridRow) {
      gridRow_ = gridRow;
   }

   public void setGridCol(Integer gridCol) {
      gridCol_ = gridCol;
   }

   //For debugging
   @Override
   public String toString() {
      if (specialFlag_ == SpecialFlag.AcqusitionFinished) {
         return "Acq finished event";
      } else if (specialFlag_ == SpecialFlag.AcqusitionSequenceEnd) {
         return "Acq sequence end event";
      }

      StringBuilder builder = new StringBuilder();
      if (zPosition_ != null) {
         builder.append("z " + zPosition_);
      }
      if (xPosition_ != null) {
         builder.append("x " + xPosition_);
      }
      if (yPosition_ != null) {
         builder.append("y  " + yPosition_);
      }
      if (gridRow_ != null) {
         builder.append("row  " + gridRow_);
      }
      if (gridCol_ != null) {
         builder.append("col   " + gridCol_);
      }

      for (String axis : axisPositions_.keySet()) {
         builder.append("\t" + axis + ": " + axisPositions_.get(axis));
      }

      return builder.toString();
   }

}

class ThreeTuple implements Comparable<ThreeTuple> {

   final String dev, prop, val;

   public ThreeTuple(String d, String p, String v) {
      dev = d;
      prop = p;
      val = v;
   }

   public String[] toArray() {
      return new String[]{dev, prop, val};
   }

   @Override
   public int compareTo(ThreeTuple t) {
      if (!dev.equals(t.dev)) {
         return dev.compareTo(dev);
      } else {
         return prop.compareTo(prop);
      }
   }

}
