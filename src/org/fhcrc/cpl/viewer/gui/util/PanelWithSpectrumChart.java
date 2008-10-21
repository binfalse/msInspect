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

package org.fhcrc.cpl.viewer.gui.util;

import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.LookupPaintScale;
import org.apache.log4j.Logger;
import org.fhcrc.cpl.viewer.MSRun;
import org.fhcrc.cpl.viewer.feature.Spectrum;
import org.fhcrc.cpl.toolbox.FloatRange;
import org.fhcrc.cpl.toolbox.BasicStatistics;
import org.fhcrc.cpl.toolbox.gui.chart.*;

import javax.imageio.ImageIO;
import java.util.*;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.awt.*;

/**
 * PanelWithChart implementation to make it easy to put out Line Charts
 * If you want to do anything super-serious with the chart, use
 * getChart() and getRenderer()
 */
public class PanelWithSpectrumChart extends PanelWithHeatMap
{
    static Logger _log = Logger.getLogger(PanelWithSpectrumChart.class);

    protected MSRun run;

    protected int minScan = 0;
    protected int maxScan = 0;

    protected float minMz = 0;
    protected float maxMz = 0;

    protected int resolution = DEFAULT_RESOLUTION;

    public static final int DEFAULT_RESOLUTION = 100;

    protected int lightFirstScanLine = 0;
    protected int lightLastScanLine = 0;
    protected int heavyFirstScanLine = 0;
    protected int heavyLastScanLine = 0;

    protected float lightMz = 0;
    protected float heavyMz = 0;

    protected boolean generateLineCharts = false;

    protected Map<Integer, PanelWithLineChart> scanLineChartMap = null;

    protected boolean generate3DChart = false;
    protected PanelWithRPerspectivePlot contourPlot = null;
    protected int contourPlotWidth = DEFAULT_CONTOUR_PLOT_WIDTH;
    protected int contourPlotHeight = DEFAULT_CONTOUR_PLOT_HEIGHT;
    protected int contourPlotRotationAngle = DEFAULT_CONTOUR_PLOT_ROTATION;
    protected int contourPlotTiltAngle = DEFAULT_CONTOUR_PLOT_TILT;
    protected boolean contourPlotShowAxes = DEFAULT_CONTOUR_PLOT_SHOW_AXES;


    public static final int DEFAULT_CONTOUR_PLOT_WIDTH = 1000;
    public static final int DEFAULT_CONTOUR_PLOT_HEIGHT = 1000;
    public static final int DEFAULT_CONTOUR_PLOT_ROTATION = 80;
    public static final int DEFAULT_CONTOUR_PLOT_TILT = 20;
    public static final boolean DEFAULT_CONTOUR_PLOT_SHOW_AXES = true;

    //this is a bit of a hack.  While we're in here, supply the scan level of a particular scan
    //scan to return the level for
    protected int idEventScan = -1;
    protected float idEventMz = -1;

    protected boolean specifiedScanFoundMS1 = false;


    public PanelWithSpectrumChart()
    {
        super();
    }

    protected void init()
    {
        super.init();
        setPalette(PanelWithHeatMap.PALETTE_POSITIVE_WHITE_BLUE_NEGATIVE_BLACK_RED);
        setAxisLabels("scan", "m/z");
        scanLineChartMap = new HashMap<Integer, PanelWithLineChart>();
    }

    public PanelWithSpectrumChart(MSRun run, int minScan, int maxScan, float minMz, float maxMz,
                                  int lightFirstScanLine, int lightLastScanLine,
                                  int heavyFirstScanLine, int heavyLastScanLine,
                                  float lightMz, float heavyMz)
    {
        init();

        this.run = run;
        this.minScan = minScan;
        this.maxScan = maxScan;
        this.minMz = minMz;
        this.maxMz = maxMz;
        this.lightFirstScanLine = lightFirstScanLine;
        this.lightLastScanLine = lightLastScanLine;
        this.heavyFirstScanLine = heavyFirstScanLine;
        this.heavyLastScanLine = heavyLastScanLine;
        this.lightMz = lightMz;
        this.heavyMz = heavyMz;
    }

    /**
     *
     */
    public void generateChart()
    {
        int minScanIndex = Math.abs(run.getIndexForScanNum(minScan));
        int maxScanIndex = Math.abs(run.getIndexForScanNum(maxScan));
        int numScans = maxScanIndex - minScanIndex + 1;

        int numMzBins = (int) ((float) resolution * (maxMz - minMz)) + 1;

        double[] scanValues = new double[numScans];
        double[] scanIndexValues = new double[numScans];

        double[] mzValues = new double[numMzBins];

        for (int i=0; i<numScans; i++)
        {
            scanIndexValues[i] = minScanIndex + i;
            scanValues[i] = run.getScanNumForIndex(minScanIndex + i);
        }
        for (int i=0; i<numMzBins; i++)
            mzValues[i] = minMz + (i / (float) resolution);

        //Resampling adds some slop on each end
        float minMzForRange = (int) minMz;
        if (minMzForRange > minMz) minMzForRange--;
        float maxMzForRange = (int) maxMz;
        if (maxMzForRange < maxMz) maxMzForRange++;


        FloatRange mzWindowForResample = new FloatRange(minMzForRange, maxMzForRange);
        double[][] intensityValues = new double[numScans][numMzBins];
        _log.debug("Loading spectrum in range....");

        double maxIntensityOnChart = 0;

        for (int scanArrayIndex = 0; scanArrayIndex < numScans; scanArrayIndex++)
        {
            int scanIndex = minScanIndex + scanArrayIndex;
            MSRun.MSScan scan = run.getScan(scanIndex);
            if (scan.getNum() == idEventScan)
                specifiedScanFoundMS1 = true;
            float[] signalFromResample = Spectrum.Resample(scan.getSpectrum(), mzWindowForResample, resolution);
            int firstIndexToKeep = -1;

            for (int i=0; i<signalFromResample.length; i++)
            {
                float mzValueThisIndex = minMzForRange + (i / (float) resolution);
                if (mzValueThisIndex >= minMz && firstIndexToKeep == -1)
                {
                    firstIndexToKeep = i;
                    break;
                }
            }

            //this is horrible.  arraycopy would be better, but need to convert float to double
            double[] signal = new double[numMzBins];
            for (int i=0; i<numMzBins; i++)
                signal[i] = signalFromResample[firstIndexToKeep + i];

            if (generateLineCharts)
            {
                PanelWithLineChart lineChart =
                        new PanelWithPeakChart(mzValues, signal, "Scan " + (int) scanValues[scanArrayIndex]);
                lineChart.setAxisLabels("m/z", "intensity");
                scanLineChartMap.put((int) scanValues[scanArrayIndex], lineChart);
            }

            intensityValues[scanArrayIndex] = signal;

            maxIntensityOnChart = Math.max(maxIntensityOnChart, BasicStatistics.max(signal));
        }

        if (generateLineCharts)
        {
            for (PanelWithLineChart lineChart : scanLineChartMap.values())
            {
                ((XYPlot) lineChart.getPlot()).getRangeAxis().setRange(0, maxIntensityOnChart);
            }
        }


        int lightFirstScanLineIndex = -1;
        int lightLastScanLineIndex = -1;
        int heavyFirstScanLineIndex = -1;
        int heavyLastScanLineIndex = -1;

        int numScansPadded = maxScan - minScan + 1;
        double[] scanValuesPadded;
        double[][] intensityValuesPadded;

        setPaintScale(createHeatMapPaintScale());

        if (numScansPadded == numScans)
        {
            scanValuesPadded = scanValues;
            intensityValuesPadded = intensityValues;

            if (lightFirstScanLine > 0)
            {
                for (int i=0; i<scanValues.length; i++)
                {
                    if (scanValues[i] < lightFirstScanLine)
                        lightFirstScanLineIndex = i;
                    else break;
                }
            }
            if (lightLastScanLine > 0)
            {
                for (int i=0; i<scanValues.length; i++)
                {
                    if (scanValues[i] <= lightLastScanLine +1)
                        lightLastScanLineIndex = i;
                    else break;
                }
            }
            if (heavyFirstScanLine > 0)
            {
                for (int i=0; i<scanValues.length; i++)
                {
                    if (scanValues[i] < heavyFirstScanLine)
                        heavyFirstScanLineIndex = i;
                    else break;
                }
            }
            if (heavyLastScanLine > 0)
            {
                for (int i=0; i<scanValues.length; i++)
                {
                    if (scanValues[i] <= heavyLastScanLine +1)
                        heavyLastScanLineIndex = i;
                    else break;
                }
            }
        }
        else
        {
            _log.debug("Padding! unpadded: " + numScans + ", padded: " + numScansPadded);

            scanValuesPadded = new double[numScansPadded];
            intensityValuesPadded = new double[numScansPadded][numMzBins];

            int unPaddedIndex = 0;

            for (int i=0; i<scanValuesPadded.length; i++)
            {
                int scanValue = minScan + i;
                scanValuesPadded[i] = scanValue;

                if (unPaddedIndex < scanValues.length-1 && scanValue >= scanValues[unPaddedIndex+1])
                    unPaddedIndex++;

                System.arraycopy(intensityValues[unPaddedIndex], 0, intensityValuesPadded[i], 0,
                        intensityValues[unPaddedIndex].length);
            }

            //add the lines for the scanlines, just outside the specified boundaries
            if (lightFirstScanLine > 0)
            {
                lightFirstScanLineIndex = 0;
                for (int i=0; i<scanValuesPadded.length; i++)
                {
                    if (scanValuesPadded[i] < lightFirstScanLine)
                    {
                        lightFirstScanLineIndex = i;
                    }
                    else break;
                }
            }
            if (lightLastScanLine > 0)
            {
                lightLastScanLineIndex = 0;
                int nextScanAfterLine2 = 0;
                for (int i=0; i<scanValues.length; i++)
                    if (scanValues[i] > lightLastScanLine)
                    {
                        nextScanAfterLine2 = (int) scanValues[i];
                        break;
                    }
                if (nextScanAfterLine2 == 0)
                    lightLastScanLineIndex = 0;
                else
                {
                    for (int i=0; i<scanValuesPadded.length; i++)
                    {
                        if (scanValuesPadded[i] >= nextScanAfterLine2)
                        {
                            lightLastScanLineIndex = i;
                            break;
                        }
                    }
                }
            }
            if (heavyFirstScanLine > 0)
            {
                heavyFirstScanLineIndex = 0;
                for (int i=0; i<scanValuesPadded.length; i++)
                {
                    if (scanValuesPadded[i] < heavyFirstScanLine)
                    {
                        heavyFirstScanLineIndex = i;
                    }
                    else break;
                }
            }
            if (heavyLastScanLine > 0)
            {
                heavyLastScanLineIndex = 0;
                int nextScanAfterLine2 = 0;
                for (int i=0; i<scanValues.length; i++)
                    if (scanValues[i] > heavyLastScanLine)
                    {
                        nextScanAfterLine2 = (int) scanValues[i];
                        break;
                    }
                if (nextScanAfterLine2 == 0)
                    heavyLastScanLineIndex = 0;
                else
                {
                    for (int i=0; i<scanValuesPadded.length; i++)
                    {
                        if (scanValuesPadded[i] >= nextScanAfterLine2)
                        {
                            heavyLastScanLineIndex = i;
                            break;
                        }
                    }
                }
            }
        }
        if (heavyFirstScanLineIndex > 0)
            for (int j=0; j<intensityValuesPadded[heavyFirstScanLineIndex].length; j++)
            {
                if (mzValues[j] > lightMz)
                    break;
                double origValue = intensityValuesPadded[heavyFirstScanLineIndex][j];
                double newValue = -origValue;
                if (newValue == 0)
                    newValue = -0.000001;
                intensityValuesPadded[heavyFirstScanLineIndex][j] = newValue;
            }
        if (heavyLastScanLineIndex > 0)
            for (int j=0; j<intensityValuesPadded[heavyLastScanLineIndex].length; j++)
            {
                if (mzValues[j] > lightMz)
                    break;
                double origValue = intensityValuesPadded[heavyLastScanLineIndex][j];
                double newValue = -origValue;
                if (newValue == 0)
                    newValue = -0.000001;
                intensityValuesPadded[heavyLastScanLineIndex][j] = newValue;
            }
        if (heavyFirstScanLineIndex > 0)
            for (int j=0; j<intensityValuesPadded[heavyFirstScanLineIndex].length; j++)
            {
                if (mzValues[j] < heavyMz)
                    continue;
                double origValue = intensityValuesPadded[heavyFirstScanLineIndex][j];
                double newValue = -origValue;
                if (newValue == 0)
                    newValue = -0.000001;
                intensityValuesPadded[heavyFirstScanLineIndex][j] = newValue;
            }
        if (heavyLastScanLineIndex > 0)
            for (int j=0; j<intensityValuesPadded[heavyLastScanLineIndex].length; j++)
            {
                if (mzValues[j] < heavyMz)
                    continue;
                double origValue = intensityValuesPadded[heavyLastScanLineIndex][j];
                double newValue = -origValue;
                if (newValue == 0)
                    newValue = -0.000001;
                intensityValuesPadded[heavyLastScanLineIndex][j] = newValue;
            }
        float intensityForTickMark = -0.001f;
        float intensityForIdCross = -0.001f;


        //cross for ID event
        if (idEventScan > 0 && idEventMz > 0)
        {
            int closestEventMzIndex = Math.abs(Arrays.binarySearch(mzValues, idEventMz));
            int closestEventScanIndex = Math.abs(Arrays.binarySearch(scanValuesPadded, idEventScan));

            intensityValuesPadded[closestEventScanIndex-1][closestEventMzIndex-1] = intensityForIdCross;
            intensityValuesPadded[closestEventScanIndex][closestEventMzIndex] = intensityForIdCross;
            intensityValuesPadded[closestEventScanIndex+1][closestEventMzIndex+1] = intensityForIdCross;
            intensityValuesPadded[closestEventScanIndex-1][closestEventMzIndex+1] = intensityForIdCross;
            intensityValuesPadded[closestEventScanIndex+1][closestEventMzIndex-1] = intensityForIdCross;
        }

        //tick marks for specified m/z's
        if (lightMz > 0)
        {
            int closestLightMzIndex = Math.abs(Arrays.binarySearch(mzValues, lightMz));
            intensityValuesPadded[0][closestLightMzIndex] = intensityForTickMark;
            intensityValuesPadded[1][closestLightMzIndex] = intensityForTickMark;

            intensityValuesPadded[intensityValuesPadded.length-1][closestLightMzIndex] = intensityForTickMark;
            intensityValuesPadded[intensityValuesPadded.length-2][closestLightMzIndex] = intensityForTickMark;
        }
        if (heavyMz > 0)
        {
            int closestHeavyMzIndex = Math.abs(Arrays.binarySearch(mzValues, heavyMz));
            intensityValuesPadded[0][closestHeavyMzIndex] = intensityForTickMark;
            intensityValuesPadded[1][closestHeavyMzIndex] = intensityForTickMark;

            intensityValuesPadded[intensityValuesPadded.length-1][closestHeavyMzIndex] = intensityForTickMark;
            intensityValuesPadded[intensityValuesPadded.length-2][closestHeavyMzIndex] = intensityForTickMark;

        }

        if (generate3DChart)
        {
            _log.debug("Generating R contour plot...");

//float[][] intensityValuesPaddedAsFloat = new float[intensityValuesPadded.length][intensityValuesPadded[0].length];
//for (int i=0; i<intensityValuesPadded.length; i++)
//    for (int j=0; j<intensityValuesPadded[0].length; j++)
//       intensityValuesPaddedAsFloat[i][j] = (float) intensityValuesPadded[i][j];
//
//JFrame frame = SurfaceFrame.ShowSurfaceFrame(intensityValuesPaddedAsFloat);
//frame.setVisible(true);

            contourPlot = new  PanelWithRPerspectivePlot();
            contourPlot.setRotationAngle(contourPlotRotationAngle);
            contourPlot.setTiltAngle(contourPlotTiltAngle);
            contourPlot.setChartWidth(contourPlotWidth);
            contourPlot.setChartHeight(contourPlotHeight);
            contourPlot.setUseGradientForColor(true);
            contourPlot.setShowBorders(false);
            contourPlot.setShowBox(contourPlotShowAxes);

//contourPlot.setTiltAngles(Arrays.asList(new Integer[] { 82, 76, 70, 66, 60, 54, 49, 45, 41, 38, 35, 32, 30, 28, 27, 26, 25, 24, 23 }));
// contourPlot.setRotationAngles(Arrays.asList(new Integer[] {0, 5, 10, 15, 20, 25, 30, 35, 40, 45,
//                    50, 55, 60, 65, 70, 75, 80, 85, 85}));


            double[] twoZeroes = new double[] {0,0};
            if (lightFirstScanLine > 0)
            {
                double[] line1XValues = new double[] {lightFirstScanLine, lightFirstScanLine};
                double[] line1YValues = new double[] {minMz, lightMz};
                contourPlot.addLine(line1XValues, line1YValues, twoZeroes, "red");
            }
            if (lightLastScanLine > 0)
            {
                double[] line2XValues = new double[] {lightLastScanLine, lightLastScanLine};
                double[] line2YValues = new double[] {minMz, lightMz};
                contourPlot.addLine(line2XValues, line2YValues, twoZeroes, "red");
            }
            if (heavyFirstScanLine > 0)
            {
                double[] line1XValues = new double[] {heavyFirstScanLine, heavyFirstScanLine};
                double[] line1YValues = new double[] {heavyMz, maxMz};
                contourPlot.addLine(line1XValues, line1YValues, twoZeroes, "red");
            }
            if (heavyLastScanLine > 0)
            {
                double[] line2XValues = new double[] {heavyLastScanLine, heavyLastScanLine};
                double[] line2YValues = new double[] {heavyMz, maxMz};
                contourPlot.addLine(line2XValues, line2YValues, twoZeroes, "red");
            }
            //draw a little X on the MS/MS event
            if (idEventScan > 0 && idEventMz > 0)
            {
                double crossTop = idEventMz+0.1;
                double crossBottom = idEventMz-0.1;
                double crossLeft = idEventScan-2;
                double crossRight = idEventScan+2;
//plus
//                contourPlot.addLine(new double[] {plusLeft, plusRight},
//                                    new double[]{idEventMz, idEventMz}, twoZeroes, "yellow");
//                contourPlot.addLine(new double[] {idEventScan, idEventScan},
//                                    new double[] {crossTop, crossBottom}, twoZeroes, "yellow");
                contourPlot.addLine(new double[] {crossLeft, crossRight},
                                    new double[] {crossTop, crossBottom}, twoZeroes, "blue");
                contourPlot.addLine(new double[] {crossLeft, crossRight},
                                    new double[] {crossBottom, crossTop}, twoZeroes, "blue");
            }

            double closestLightMz = mzValues[Math.abs(Arrays.binarySearch(mzValues, lightMz))];
            double closestHeavyMz = mzValues[Math.abs(Arrays.binarySearch(mzValues, heavyMz))];

            double[] tickXValues = new double[] { minScan-1, maxScan+1 };

            double[] lightTickYValues = new double[] { closestLightMz, closestLightMz };
            double[] heavyTickYValues = new double[] { closestHeavyMz, closestHeavyMz };

            contourPlot.addLine(tickXValues, lightTickYValues, twoZeroes, "red");
            contourPlot.addLine(tickXValues, heavyTickYValues, twoZeroes, "red");  

            contourPlot.plot(scanValuesPadded, mzValues, intensityValuesPadded);
            _log.debug("Generated R contour plot.");
        }

        _log.debug("Done loading spectrum in range.");

        setData(scanValuesPadded, mzValues, intensityValuesPadded);
//try {contourPlot.saveAllImagesToFiles(new File("/home/dhmay/temp/charts"));} catch(IOException e) {}
        ((XYPlot) _plot).getDomainAxis().setRange(minScan, maxScan);
        ((XYPlot) _plot).getRangeAxis().setRange(minMz, maxMz);
    }


    /**
     * Create a paint scale that cycles from white to blue in the positive range, and red to blue in the negative
     * @return
     */
    protected LookupPaintScale createHeatMapPaintScale()
    {
        LookupPaintScale result = new LookupPaintScale(lowerZBound, upperZBound+0.1, Color.RED);
        addValuesToPaintScale(result, 0, upperZBound, Color.WHITE, Color.BLUE);
        addValuesToPaintScale(result, -upperZBound-0.000001, -0.0000001, Color.BLUE, Color.RED);
        return result;
    }

    /**
     *
     * @param imageWidthEachScan
     * @param imageHeightEachScan
     * @param maxTotalImageHeight a hard boundary on the total image height.  If imageHeightEachScan is too big,
     * given the total number of charts and this arg, it gets knocked down
     * @param outputFile
     * @throws java.io.IOException
     */
    public void savePerScanSpectraImage(int imageWidthEachScan, int imageHeightEachScan, int maxTotalImageHeight,
                                        File outputFile)
            throws IOException
    {
        int numCharts = scanLineChartMap.size();

        int widthPaddingForLabels = 50;

        imageHeightEachScan = Math.min(imageHeightEachScan, maxTotalImageHeight / numCharts);

        List<Integer> allScanNumbers = new ArrayList<Integer>(scanLineChartMap.keySet());
        Collections.sort(allScanNumbers);
        List<PanelWithChart> allCharts = new ArrayList<PanelWithChart>();

        for (int scanNumber : allScanNumbers)
        {
            PanelWithLineChart scanChart = scanLineChartMap.get(scanNumber);
            allCharts.add(scanChart);
            scanChart.setSize(imageWidthEachScan - widthPaddingForLabels, imageHeightEachScan);
        }                 

        BufferedImage perScanChartImage = MultiChartDisplayPanel.createImageForAllCharts(allCharts);

        BufferedImage perScanChartImageWithLabels = new BufferedImage(imageWidthEachScan,
                perScanChartImage.getHeight(), BufferedImage.TYPE_INT_RGB);

        Graphics2D g = perScanChartImageWithLabels.createGraphics();
        g.drawImage(perScanChartImage, widthPaddingForLabels, 0, null);
        g.setPaint(Color.WHITE);
        g.drawRect(0, 0, widthPaddingForLabels, perScanChartImage.getHeight());

        for (int i=0; i<allCharts.size(); i++)
        {
            int scanNumber = allScanNumbers.get(i);

            int chartTop = i * imageHeightEachScan;
            int chartMiddle = chartTop + (imageHeightEachScan / 2);

            if (lightFirstScanLine > 0 && lightLastScanLine > 0)
            {
                if (scanNumber >= lightFirstScanLine && scanNumber <= lightLastScanLine)
                    g.setPaint(Color.GREEN);
                else
                    g.setPaint(Color.RED);
            }
            else g.setPaint(Color.BLACK);

            g.drawString("" + scanNumber, 5, chartMiddle);
        }
        g.dispose();

        ImageIO.write(perScanChartImageWithLabels,"png",outputFile);
    }

    /*        Tooltips don't work with XYBlockRenderer
    protected class SpectrumToolTipGenerator implements XYZToolTipGenerator
    {
        protected double[] scanValues;
        protected double[] mzValues;

        public SpectrumToolTipGenerator(double[] scanValues, double[] mzValues)
        {
            this.scanValues = scanValues;
            this.mzValues = mzValues;
        }

        public String generateToolTip(XYDataset data, int series, int item)
        {
System.err.println("asdf1");
            return "scan " + scanValues[series] + ", m/z " + mzValues[item];
        }

        public String generateToolTip(XYZDataset data, int series, int item)
        {
System.err.println("asdf2");            

            return "scan " + scanValues[series] + ", m/z " + mzValues[item];
        }
    }
    */

    public int getMinScan()
    {
        return minScan;
    }

    public void setMinScan(int minScan)
    {
        this.minScan = minScan;
    }

    public int getMaxScan()
    {
        return maxScan;
    }

    public void setMaxScan(int maxScan)
    {
        this.maxScan = maxScan;
    }

    public float getMinMz()
    {
        return minMz;
    }

    public void setMinMz(float minMz)
    {
        this.minMz = minMz;
    }

    public float getMaxMz()
    {
        return maxMz;
    }

    public void setMaxMz(float maxMz)
    {
        this.maxMz = maxMz;
    }

    public int getResolution()
    {
        return resolution;
    }

    public void setResolution(int resolution)
    {
        this.resolution = resolution;
    }

    public MSRun getRun()
    {
        return run;
    }

    public void setRun(MSRun run)
    {
        this.run = run;
    }

    public int getLightFirstScanLine()
    {
        return lightFirstScanLine;
    }

    public void setLightFirstScanLine(int lightFirstScanLine)
    {
        this.lightFirstScanLine = lightFirstScanLine;
    }

    public int getLightLastScanLine()
    {
        return lightLastScanLine;
    }

    public void setLightLastScanLine(int lightLastScanLine)
    {
        this.lightLastScanLine = lightLastScanLine;
    }

    public int getHeavyFirstScanLine()
    {
        return heavyFirstScanLine;
    }

    public void setHeavyFirstScanLine(int heavyFirstScanLine)
    {
        this.heavyFirstScanLine = heavyFirstScanLine;
    }

    public int getHeavyLastScanLine()
    {
        return heavyLastScanLine;
    }

    public void setHeavyLastScanLine(int heavyLastScanLine)
    {
        this.heavyLastScanLine = heavyLastScanLine;
    }

    public Map<Integer, PanelWithLineChart> getScanLineChartMap()
    {
        return scanLineChartMap;
    }

    public void setScanLineChartMap(Map<Integer, PanelWithLineChart> scanLineChartMap)
    {
        this.scanLineChartMap = scanLineChartMap;
    }

    public boolean isSpecifiedScanFoundMS1()
    {
        return specifiedScanFoundMS1;
    }

    public void setSpecifiedScanFoundMS1(boolean specifiedScanFoundMS1)
    {
        this.specifiedScanFoundMS1 = specifiedScanFoundMS1;
    }

    public boolean isGenerate3DChart()
    {
        return generate3DChart;
    }

    public void setGenerate3DChart(boolean generate3DChart)
    {
        this.generate3DChart = generate3DChart;
    }

    public PanelWithRPerspectivePlot getContourPlot()
    {
        return contourPlot;
    }

    public void setContourPlot(PanelWithRPerspectivePlot contourPlot)
    {
        this.contourPlot = contourPlot;
    }

    public int getContourPlotWidth()
    {
        return contourPlotWidth;
    }

    public void setContourPlotWidth(int contourPlotWidth)
    {
        this.contourPlotWidth = contourPlotWidth;
    }

    public int getContourPlotHeight()
    {
        return contourPlotHeight;
    }

    public void setContourPlotHeight(int contourPlotHeight)
    {
        this.contourPlotHeight = contourPlotHeight;
    }

    public boolean getContourPlotShowAxes()
    {
        return contourPlotShowAxes;
    }

    public void setContourPlotShowAxes(boolean contourPlotShowAxes)
    {
        this.contourPlotShowAxes = contourPlotShowAxes;
    }

    public int getContourPlotRotationAngle()
    {
        return contourPlotRotationAngle;
    }

    public void setContourPlotRotationAngle(int contourPlotRotationAngle)
    {
        this.contourPlotRotationAngle = contourPlotRotationAngle;
    }

    public int getContourPlotTiltAngle()
    {
        return contourPlotTiltAngle;
    }

    public void setContourPlotTiltAngle(int contourPlotTiltAngle)
    {
        this.contourPlotTiltAngle = contourPlotTiltAngle;
    }

    public boolean isGenerateLineCharts()
    {
        return generateLineCharts;
    }

    public void setGenerateLineCharts(boolean generateLineCharts)
    {
        this.generateLineCharts = generateLineCharts;
    }

    public int getIdEventScan()
    {
        return idEventScan;
    }

    public void setIdEventScan(int idEventScan)
    {
        this.idEventScan = idEventScan;
    }

    public float getIdEventMz()
    {
        return idEventMz;
    }

    public void setIdEventMz(float idEventMz)
    {
        this.idEventMz = idEventMz;
    }
}