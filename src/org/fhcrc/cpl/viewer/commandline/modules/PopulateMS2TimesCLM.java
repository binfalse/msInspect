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
import org.fhcrc.cpl.viewer.feature.FeatureSet;
import org.fhcrc.cpl.viewer.MSRun;
import org.fhcrc.cpl.toolbox.ApplicationContext;
import org.apache.log4j.Logger;

import java.io.File;


/**
 * Command linemodule for feature finding
 */
public class PopulateMS2TimesCLM extends BaseCommandLineModuleImpl
        implements CommandLineModule
{
    protected static Logger _log = Logger.getLogger(PopulateMS2TimesCLM.class);

    protected File[] featureFiles;
    protected File outDir;

    protected File mzXmlDir;

    public PopulateMS2TimesCLM()
    {
        init();
    }

    protected void init()
    {
        mCommandName = "populatems2times";
        mShortDescription = "Populate clock time information for a set of MS2 features";
        mHelpMessage = "This tool reaches back to the mzXML file to populate the clock times of each MS2 feature, based on scan number";

        CommandLineArgumentDefinition[] argDefs =
                {
                        createUnnamedSeriesArgumentDefinition(ArgumentDefinitionFactory.FILE_TO_READ,false, null),
                        createDirectoryToReadArgumentDefinition("mzxmldir",true,""),
                        createDirectoryToReadArgumentDefinition("outdir",false, null)
                };
        addArgumentDefinitions(argDefs);
    }

    public void assignArgumentValues()
            throws ArgumentValidationException
    {
        outDir = getFileArgumentValue("outdir");
        featureFiles = getUnnamedSeriesFileArgumentValues();

        mzXmlDir = getFileArgumentValue("mzxmldir");
    }


    /**
     * do the actual work
     */
    public void execute() throws CommandLineModuleExecutionException
    {
        try
        {
            for (File featureFile : featureFiles)
            {
                FeatureSet featureSet = new FeatureSet(featureFile);
                String ms2SourceFileName = featureSet.getSourceFile().getName();
                String mzXmlFileName =
                        (ms2SourceFileName.substring(0, ms2SourceFileName.indexOf(".")) +
                                ".mzXML");
                File mzXmlFile = null;

                boolean foundIt = false;
                for (String potentialMzXmlFilename : mzXmlDir.list())
                {
                    if (potentialMzXmlFilename.equalsIgnoreCase(mzXmlFileName))
                    {
                        mzXmlFile = new File(mzXmlDir.getAbsolutePath() + File.separatorChar +
                                potentialMzXmlFilename);
                        MSRun run = MSRun.load(mzXmlFile.getAbsolutePath());
                        ApplicationContext.setMessage("Located mzXML file " + potentialMzXmlFilename);

                        featureSet.populateTimesForMS2Features(run);
                        foundIt = true;
                        break;
                    }
                }
                if (!foundIt)
                    throw new CommandLineModuleExecutionException("Couldn't find source mzXML file for feature file " +ms2SourceFileName );

                File outFile = new File(outDir, ms2SourceFileName);
                featureSet.save(outFile);
            }
        }
        catch (Exception e)
        {
            throw new CommandLineModuleExecutionException(e);
        }
    }

}
