/* 
 * Copyright (c) 2003-2007 Fred Hutchinson Cancer Research Center
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
package org.fhcrc.cpl.viewer.commandline.modules;

import org.fhcrc.cpl.viewer.commandline.*;
import org.fhcrc.cpl.viewer.commandline.arguments.ArgumentValidationException;
import org.fhcrc.cpl.viewer.commandline.arguments.CommandLineArgumentDefinition;
import org.fhcrc.cpl.viewer.commandline.arguments.ArgumentDefinitionFactory;
import org.fhcrc.cpl.viewer.MSRun;
import org.fhcrc.cpl.viewer.Application;
import org.fhcrc.cpl.viewer.feature.FeatureSet;
import org.fhcrc.cpl.viewer.util.SharedProperties;
import org.fhcrc.cpl.viewer.gui.MSImageComponent;
import org.labkey.common.tools.ApplicationContext;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.beans.PropertyChangeEvent;


/**
 * Command linemodule for feature finding
 */
public class SaveImageCommandLineModule extends BaseCommandLineModuleImpl
        implements CommandLineModule
{
    protected static Logger _log = Logger.getLogger(SaveImageCommandLineModule.class);

    protected File[] mzxmlFiles = null;
    protected File outFile = null;
    protected File outDir = null;


    protected int maxWidth = Integer.MAX_VALUE;
    protected int maxHeight = Integer.MAX_VALUE;

    protected boolean includeTIC = false;

//    protected FeatureSet featureSet;

    public SaveImageCommandLineModule()
    {
        init();
    }

    protected void init()
    {
        mCommandName = "saveimage";
        mShortDescription = "Create an image file containing a visualization of a run";
        mHelpMessage = "This command loads an mzXml file and writes out an image in which " +
                "the X axis represents time, the Y axis represents M/Z, and the shade of each " +
                "pixel represents intensity.";

        CommandLineArgumentDefinition[] argDefs =
               {
                    createUnnamedSeriesFileArgumentDefinition(
                            true, "Input mzXml file(s)"),
                    createFileToWriteArgumentDefinition("out", false, "Output image file"),
                    createDirectoryToReadArgumentDefinition("outdir", false, "Output image directory"),

                    createIntegerArgumentDefinition("maxwidth", false, "Maximum width of output image", maxWidth),
                    createIntegerArgumentDefinition("maxheight", false, "Maximum height of output image", maxHeight),
                    createBooleanArgumentDefinition("includetic", false, "Include Total Ion Chromatogram?", includeTIC),
//                       createFeatureFileArgumentDefinition("features", false, "Features to display"),

               };
        addArgumentDefinitions(argDefs);
    }

    public void assignArgumentValues()
            throws ArgumentValidationException
    {
        mzxmlFiles = getUnnamedSeriesFileArgumentValues();
        outFile = getFileArgumentValue("out");
        outDir = getFileArgumentValue("outdir");

        if (mzxmlFiles.length > 1)
            assertArgumentPresent("outdir");

        if (hasArgumentValue("outdir") && hasArgumentValue("out"))
            throw new ArgumentValidationException("Can't specify both out and outdir arguments");


        maxWidth = getIntegerArgumentValue("maxwidth");
        maxHeight = getIntegerArgumentValue("maxheight");

        includeTIC = getBooleanArgumentValue("includetic");

//        featureSet = getFeatureSetArgumentValue("features");
    }

    protected String createOutFileName(File inputFile)
    {
        String fileName = inputFile.getName();
        if (fileName.toLowerCase().endsWith(".mzxml"))
            fileName = fileName.substring(0, fileName.length()-".mzxml".length());
        fileName = fileName + ".png";
        return fileName;
    }


    /**
     * do the actual work
     */
    public void execute() throws CommandLineModuleExecutionException
    {
        if (mzxmlFiles.length == 1)
        {
            handleFile(mzxmlFiles[0], outFile == null ?
                    new File(mzxmlFiles[0].getParentFile(), createOutFileName(mzxmlFiles[0])) : outFile);
        }
        else
        {
            for (File mzxmlFile : mzxmlFiles)
            {
                ApplicationContext.setMessage("Processing file " + mzxmlFile.getAbsolutePath());
                handleFile(mzxmlFile, new File(outDir, createOutFileName(mzxmlFile)));
            }
        }
    }

    public void handleFile(File mzxmlFile, File outputFile) throws CommandLineModuleExecutionException
    {
        try
        {


            MSRun run = MSRun.load(mzxmlFile.getPath());
            MSImageComponent comp = new MSImageComponent(run.getImage());

//this doesn't work.  Too bad.
//TODO: make it work
//            if (featureSet != null)
//            {
//                List listForProperty = new ArrayList<FeatureSet>(1);
//                listForProperty.add(featureSet);
//                ApplicationContext.setProperty(SharedProperties.FEATURE_RANGES,
//                                               listForProperty);
//                comp.app_propertyChange(new PropertyChangeEvent(Application.getInstance(), SharedProperties.FEATURE_RANGES, null, listForProperty));
//            }

            comp.setRun(run);

            comp.saveImage(outputFile, maxWidth, maxHeight, includeTIC);
            ApplicationContext.infoMessage("Saved image file " + outputFile.getAbsolutePath());
        }
        catch (IOException x)
        {
            ApplicationContext.errorMessage("error loading run", x);
        }
    }

}
