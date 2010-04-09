/*
 * Copyright (c) 2003-2008 Fred Hutchinson Cancer Research Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fhcrc.cpl.viewer.gui;
import org.fhcrc.cpl.viewer.quant.gui.PanelWithSpectrumChart;
import org.fhcrc.cpl.toolbox.proteomics.MSRun;
import org.fhcrc.cpl.toolbox.proteomics.feature.Feature;
import org.fhcrc.cpl.toolbox.gui.chart.TabbedMultiChartDisplayPanel;
import org.fhcrc.cpl.toolbox.gui.chart.DropdownMultiChartDisplayPanel;
import org.fhcrc.cpl.toolbox.gui.chart.PanelWithLineChart;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentListener;
import java.awt.event.ComponentEvent;
import java.util.*;

/**
 * GUI
 */
public class FeatureViewer extends JPanel
{
    protected static Logger _log = Logger.getLogger(FeatureViewer.class);


    protected MSRun run;
    protected Feature feature;
    protected int numScansBeforeAfter = 10;
    protected float mzPaddingBelowAbove = .5f;
    protected int peakPaddingAbove = 2;
    protected int peakPaddingBelow = 2;
    protected int resolution = PanelWithSpectrumChart.DEFAULT_RESOLUTION;
    protected boolean showIndividualScans = false;

    public static final int DEFAULT_WIDTH = 800;
    public static final int DEFAULT_HEIGHT = 800;

    //everything GUI
    protected TabbedMultiChartDisplayPanel multiChartDisplay;

    public FeatureViewer(MSRun run)
    {
        super();

        this.run = run;
//        this.setTitle("Feature Viewer");

        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.PAGE_START;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.insets = new Insets(5,5,5,5);
        gbc.weighty = 1;
        gbc.weightx = 1;

        //Chart display
        multiChartDisplay = new TabbedMultiChartDisplayPanel();
        multiChartDisplay.setResizeDelayMS(0);

        addComponentListener(new MyResizeListener());

        add(multiChartDisplay, gbc);
    }

    public void displayFeature(Feature feature)
    {
        this.feature = feature;
        updateCharts();
    }

    public void updateCharts()
    {
        int selectedIndex = 1;
        if (!multiChartDisplay.getChartPanels().isEmpty())
            multiChartDisplay.getSelectedIndex();
        if (!multiChartDisplay.getChartPanels().isEmpty())
            multiChartDisplay.removeAllCharts();

        float mzPeakPaddingBelow = peakPaddingBelow / Math.abs(feature.getCharge());
        float mzPeakPaddingAbove = peakPaddingAbove / Math.abs(feature.getCharge());

        PanelWithSpectrumChart spectrumPanel =
                new PanelWithSpectrumChart(run, feature.getScanFirst() - numScansBeforeAfter ,
                        feature.getScanLast() + numScansBeforeAfter,
                        feature.getMz() - mzPeakPaddingBelow - mzPaddingBelowAbove,
                        feature.getMz() + (feature.getPeaks() / feature.charge) + mzPeakPaddingAbove ,
                        feature.getScanFirst(), feature.getScanLast(), feature.getScanFirst(), feature.getScanLast(),
                        feature.getMz(),0,feature.charge);
        spectrumPanel.setNumSafePeaks(feature.peaks);
        if (feature.comprised != null)
        {

            float[] peakMzs = new float[feature.comprised.length];
            boolean hasANullPeak = false;
            for (int i=0; i<peakMzs.length; i++)
            {
                if (feature.comprised[i] == null)
                {
                    hasANullPeak = true;
                    break;
                }
                peakMzs[i] = feature.comprised[i].mz;
            }
            if (!hasANullPeak)
                spectrumPanel.setPeakMzs(peakMzs);
        }
        spectrumPanel.setResolution(resolution);
        spectrumPanel.setGenerateLineCharts(showIndividualScans);
        spectrumPanel.setGenerate3DChart(false);
        spectrumPanel.setIdEventScan(0);
        spectrumPanel.setName("Spectrum");
        spectrumPanel.setVisible(true);
        spectrumPanel.setGenerate3DChart(true);

        DropdownMultiChartDisplayPanel multiChartPanelForScans = new DropdownMultiChartDisplayPanel();
        multiChartPanelForScans.setDisplaySlideshowButton(false);

        spectrumPanel.generateCharts();

        multiChartDisplay.addChartPanel(spectrumPanel.getIntensitySumChart());
        multiChartDisplay.addChartPanel(spectrumPanel.getContourPlot());

        if (showIndividualScans)
        {
            Map<Integer, PanelWithLineChart> scanChartMap = spectrumPanel.getScanLineChartMap();
            java.util.List<Integer> allScans = new ArrayList<Integer>(scanChartMap.keySet());
            Collections.sort(allScans);

            for (int scan : allScans)
                multiChartPanelForScans.addChartPanel(scanChartMap.get(scan));
        }

        multiChartDisplay.addChartPanel(spectrumPanel.getContourPlot());
        multiChartDisplay.addChartPanel(spectrumPanel.getIntensitySumChart());
        multiChartDisplay.addChartPanel(spectrumPanel);

        multiChartDisplay.setSelectedIndex(selectedIndex);
    }

    /**
     * Manually manage the size of the multi-chart panel
     */
    protected class MyResizeListener implements ComponentListener
    {
        public void componentResized(ComponentEvent event)
        {
              multiChartDisplay.setPreferredSize(new Dimension(getWidth(), getHeight()-5));
        }
        public void componentMoved(ComponentEvent event)  {}
        public void componentShown(ComponentEvent event)  {}
        public void componentHidden(ComponentEvent event)  {}
    }

    public int getNumScansBeforeAfter() {
        return numScansBeforeAfter;
    }

    public void setNumScansBeforeAfter(int numScansBeforeAfter) {
        this.numScansBeforeAfter = numScansBeforeAfter;
    }

    public float getMzPaddingBelowAbove() {
        return mzPaddingBelowAbove;
    }

    public void setMzPaddingBelowAbove(float mzPaddingBelowAbove) {
        this.mzPaddingBelowAbove = mzPaddingBelowAbove;
    }

    public int getPeakPaddingAbove() {
        return peakPaddingAbove;
    }

    public void setPeakPaddingAbove(int peakPaddingAbove) {
        this.peakPaddingAbove = peakPaddingAbove;
    }

    public int getPeakPaddingBelow() {
        return peakPaddingBelow;
    }

    public void setPeakPaddingBelow(int peakPaddingBelow) {
        this.peakPaddingBelow = peakPaddingBelow;
    }

    public int getResolution() {
        return resolution;
    }

    public void setResolution(int resolution) {
        this.resolution = resolution;
    }

    public boolean isShowIndividualScans() {
        return showIndividualScans;
    }

    public void setShowIndividualScans(boolean showIndividualScans) {
        this.showIndividualScans = showIndividualScans;
    }
}