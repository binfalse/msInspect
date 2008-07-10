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
package org.fhcrc.cpl.viewer.amt.commandline;

import org.fhcrc.cpl.viewer.commandline.*;
import org.fhcrc.cpl.viewer.commandline.modules.FeatureSelectionParamsCommandLineModule;
import org.fhcrc.cpl.viewer.commandline.arguments.*;
import org.fhcrc.cpl.viewer.amt.*;
import org.fhcrc.cpl.viewer.MSRun;
import org.fhcrc.cpl.toolbox.RegressionUtilities;
import org.fhcrc.cpl.viewer.feature.FeatureSet;
import org.fhcrc.cpl.toolbox.ApplicationContext;
import org.fhcrc.cpl.toolbox.proteomics.Protein;
import org.fhcrc.cpl.toolbox.proteomics.Peptide;
import org.fhcrc.cpl.toolbox.proteomics.MS2Modification;
import org.fhcrc.cpl.toolbox.Pair;
import org.apache.log4j.Logger;

import java.util.*;
import java.io.File;
import java.io.IOException;


/**
 * Command line module for creating AMT databases, either from pepXml (or other feature)
 * files, or from other AMT databases, or from random peptides pulled out of a
 * FASTA file
 */
public class AmtDatabaseCreatorCommandLineModule extends
        FeatureSelectionParamsCommandLineModule
        implements CommandLineModule
{
    protected static Logger _log = Logger.getLogger(AmtDatabaseCreatorCommandLineModule.class);


    protected File mzXmlDir = null;
    protected File ms2FeaturesDir = null;
    File pepXmlOrMS2FeatureFile = null;
    File mzXmlFile = null;
    File[] inputFiles = null;
    MSRun run = null;

    protected File outFile = null;

    protected int scanOrTimeMode = RegressionUtilities.DEFAULT_REGRESSION_MODE;

    //TODO: get rid of this?
    protected boolean robustRegression = false;

    protected double maxStudentizedResidualForRegression;
    protected double maxStudentizedResidualForInclusion;

    protected int mode=-1;

    protected static final int CREATE_AMTXML_FROM_DIRECTORIES_MODE=0;
    protected static final int CREATE_AMTXML_FROM_MS2_FEATURES_MODE=1;
    protected static final int CREATE_AMTXML_FROM_MULTIPLE_AMT_XMLS_MODE=2;
    protected static final int CREATE_AMTXML_FROM_RANDOM_PEPTIDES_MODE=3;

    protected final static String[] modeStrings = {"directories",
             "ms2featurefile","amtxmls","randompeptides"};

    protected final static String[] modeExplanations =
            {
                    "supply directories for MS2 and mzXML files",
                    "ms2featurefile: create an AMT database from a single MS2 and mzXML file",
                    "amtxmls: combine multiple existing AMT databases",
                    "randompeptides: create a database of random peptides from a FASTA file"
            };

    protected boolean align = false;

    //for building random peptide databases
    protected int numRandomPeptides = 50000;
    protected Protein[] proteinsFromFasta;

    protected boolean populateMs2Times = true;



    public AmtDatabaseCreatorCommandLineModule()
    {
        init();
    }

    protected void init()
    {
        super.init();
        mCommandName = "createamt";
        mShortDescription = "Create an AMT database to store peptide observations from several, or many, LC-MS/MS runs.";

        CommandLineArgumentDefinition[] childArgDefs =
        {
                createEnumeratedArgumentDefinition("mode",true,
                       modeStrings, modeExplanations),
                createFileToWriteArgumentDefinition("out", false, "output file"),
                createDirectoryToReadArgumentDefinition("mzxmldir", false,
                        "Directory of mzXML files (for 'directories' mode)"),
                createDirectoryToReadArgumentDefinition("ms2dir", false,
                        "Directory of MS2 feature files (for 'directories' mode)"),
                createFileToReadArgumentDefinition("ms2features", false,
                        "Input MS2 feature file (for 'ms2featurefile' mode)"),
                createFileToReadArgumentDefinition("mzxml", false,
                        "Input mzXml file (for 'ms2featurefile' mode"),
                createDecimalArgumentDefinition("maxsrforregression", false,
                        "maximum studentized residual for use in regression calculation for transforming RT to NRT",
                        AmtDatabaseBuilder.DEFAULT_MAX_STUDENTIZED_RESIDUAL_FOR_REGRESSION),
                createDecimalArgumentDefinition("maxsrforinclusion", false,
                        "maximum studentized residual for inclusion in database.  Any observation with a higher " +
                                "studentized residual, based on the RT->NRT regression, will be excluded",
                        AmtDatabaseBuilder.DEFAULT_MAX_STUDENTIZED_RESIDUAL_FOR_INCLUSION),
                createUnnamedSeriesArgumentDefinition(ArgumentDefinitionFactory.FILE_TO_READ, false,
                        "Input file (for 'ms2features' mode)"),
                createEnumeratedArgumentDefinition("scanortimemode",false,
                        "Use scans or times from features (default 'time')",
                        new String[]{"scan","time"}),
                createBooleanArgumentDefinition("align", false,
                        "use nonlinear alignment when mapping time to hydrophobicity.  This is not necessarily " +
                                "recommended, as the manageamt command has a mode ('alignallruns') for nonlinearly " +
                                "aligning all runs to a single scale that is much more effective.", align),
                createIntegerArgumentDefinition("numpeptides", false,
                        "Number of random peptides to use in database creation ('randompeptides' mode only)",
                        numRandomPeptides),
                createFastaFileArgumentDefinition("fasta", false,
                        "FASTA file to pull random peptides from ('randompeptides' mode only"),
        };
        addArgumentDefinitions(childArgDefs);
    }

    public void assignArgumentValues()
            throws ArgumentValidationException
    {
        //feature-filtering parameters
        super.assignArgumentValues();

        mode = ((EnumeratedValuesArgumentDefinition) getArgumentDefinition("mode")).getIndexForArgumentValue(getStringArgumentValue("mode"));

        maxStudentizedResidualForRegression =
                getDoubleArgumentValue("maxsrforregression");
        maxStudentizedResidualForInclusion =
                getDoubleArgumentValue("maxsrforinclusion");

        if (hasArgumentValue(CommandLineArgumentDefinition.UNNAMED_PARAMETER_VALUE_SERIES_ARGUMENT))
        {
            inputFiles = getUnnamedSeriesFileArgumentValues();
        }

        populateMs2Times = hasArgumentValue("mzxml") || hasArgumentValue("mzxmldir");

        switch (mode)
        {
            case CREATE_AMTXML_FROM_DIRECTORIES_MODE:
            {
                assertArgumentPresent("ms2dir");
                if (populateMs2Times)
                    assertArgumentPresent("mzxmldir");
                else
                    assertArgumentAbsent("mzxmldir");
                mzXmlDir = getFileArgumentValue("mzxmldir");
                ms2FeaturesDir = getFileArgumentValue("ms2dir");
                break;
            }
            case CREATE_AMTXML_FROM_MS2_FEATURES_MODE:
            {
                assertArgumentPresent("ms2features");

                pepXmlOrMS2FeatureFile = getFileArgumentValue("ms2features");
                mzXmlFile = getFileArgumentValue("mzxml");
                if (mzXmlFile != null)
                {
                    try
                    {
                        run = MSRun.load(mzXmlFile.getAbsolutePath());
                    }
                    catch (IOException e)
                    {
                        throw new ArgumentValidationException("Failed to load mzXml file", e);
                    }
                }

                break;
            }
            case CREATE_AMTXML_FROM_MULTIPLE_AMT_XMLS_MODE:
            {
                assertArgumentPresent(CommandLineArgumentDefinition.UNNAMED_PARAMETER_VALUE_SERIES_ARGUMENT);
                try
                {

                }
                catch (Exception e)
                {
                    throw new ArgumentValidationException("Failed to load amtxml file", e);
                }
                break;
            }
            case CREATE_AMTXML_FROM_RANDOM_PEPTIDES_MODE:
            {
                assertArgumentPresent("fasta");
                numRandomPeptides = getIntegerArgumentValue("numpeptides");
                proteinsFromFasta = getFastaFileArgumentValue("fasta");
            }
        }

        if (hasArgumentValue("scanortimemode"))
            if ("scan".equalsIgnoreCase(getStringArgumentValue("scanortimemode")))
                scanOrTimeMode = RegressionUtilities.REGRESSION_MODE_SCAN;

        outFile = getFileArgumentValue("out");

        align = getBooleanArgumentValue("align");
        
    }


    /**
     * do the actual work
     */
    public void execute() throws CommandLineModuleExecutionException
    {
        AmtDatabase amtDB = null;

        switch (mode)
        {
            case CREATE_AMTXML_FROM_DIRECTORIES_MODE:
            {
                try
                {
                    amtDB = AmtDatabaseBuilder.createAmtDBFromDirectories(
                                    ms2FeaturesDir, mzXmlDir,
                                    scanOrTimeMode, featureSelector,
                                    robustRegression,
                                    maxStudentizedResidualForRegression,
                                    maxStudentizedResidualForInclusion,
                                    align);
                }
                catch (Exception e)
                {
                    _log.error("Failed to build AMT database: ", e);
                }
                break;
            }            
            case CREATE_AMTXML_FROM_MS2_FEATURES_MODE:
            {
                try
                {
                    try
                    {
                        amtDB = AmtDatabaseBuilder.createAmtDBFromPepXml(
                            pepXmlOrMS2FeatureFile, run, scanOrTimeMode, featureSelector,
                            robustRegression,
                            maxStudentizedResidualForRegression,
                            maxStudentizedResidualForInclusion);
                    }
                    catch (Exception e)
                    {
                        //Failed.  Try again, treating the "pepxml" file as a feature file
                        FeatureSet featureSet = new FeatureSet(pepXmlOrMS2FeatureFile);
                        Pair<Integer,Integer> numFeaturesDummy = new Pair<Integer,Integer>(0,0);
                        if (populateMs2Times)
                            featureSet.populateTimesForMS2Features(run);
                        
                        amtDB = AmtDatabaseBuilder.createAmtDatabaseForRun(featureSet,
                            scanOrTimeMode, robustRegression, numFeaturesDummy,
                            maxStudentizedResidualForRegression,
                            maxStudentizedResidualForInclusion);
                    }
                }
                catch (Exception e)
                {
                    _log.error("Failed to build AMT database: ", e);
                }
                break;
            }
            case CREATE_AMTXML_FROM_MULTIPLE_AMT_XMLS_MODE:
            {
                amtDB = new AmtDatabase();
                try
                {
                    for (File amtXmlFile : inputFiles)
                    {
                        ApplicationContext.infoMessage("Processing file " + amtXmlFile.getAbsolutePath() + "...");
                        AmtXmlReader amtXmlReader = new AmtXmlReader(amtXmlFile);
                        AmtDatabase additionalAmtDatabase = amtXmlReader.getDatabase();

                        amtDB.addObservationsFromAnotherDatabase(additionalAmtDatabase);
                        ApplicationContext.infoMessage("\tadded database from file " +
                                amtXmlFile.getName() + ": ");
                        ApplicationContext.infoMessage(additionalAmtDatabase.toString());
                    }
                    ApplicationContext.infoMessage("Combined database:");
                    ApplicationContext.infoMessage(amtDB.toString());

                }
                catch (Exception e)
                {
                    throw new CommandLineModuleExecutionException(e);
                }
                break;
            }
            case CREATE_AMTXML_FROM_RANDOM_PEPTIDES_MODE:
            {
                Set<String> randomPeptideStrings = new HashSet<String>();
                Set<Peptide> randomPeptides = new HashSet<Peptide>();
                List<Peptide>[] digestedProteins = new List[proteinsFromFasta.length];

                //the logic for choosing random peptides is right here.  Should ideally
                //be moved down into AmtDatabaseBuilder
                while (randomPeptides.size() < numRandomPeptides)
                {
                    int proteinIndex =
                            (int) Math.round(Math.random() * proteinsFromFasta.length);
                    //this skews things slightly, but, whatever
                    if (proteinIndex == proteinsFromFasta.length) proteinIndex--;

                    if (digestedProteins[proteinIndex] == null)
                    {
                        List<Peptide> peptideList =
                                ProteinMatcher.generatePeptidesFromProtein(
                                        proteinsFromFasta[proteinIndex], 0, 5);
                        digestedProteins[proteinIndex] = peptideList;
                    }

                    if (digestedProteins[proteinIndex].size() == 0)
                        continue;

                    int tries=0;
                    boolean chosen=false;
                    while (!chosen && tries < 20)
                    {
                        int peptideIndex =
                                (int) Math.round(Math.random() *
                                digestedProteins[proteinIndex].size());
                        if (peptideIndex == digestedProteins[proteinIndex].size())
                            peptideIndex--;
                        Peptide randomPeptide =
                                digestedProteins[proteinIndex].get(peptideIndex);
                        if (!randomPeptideStrings.contains(
                                new String(randomPeptide.getChars())))
                        {
                            chosen=true;
                            randomPeptides.add(randomPeptide);
                            randomPeptideStrings.add(new String(randomPeptide.getChars()));
                        }
                        tries++;
                    }
                }

                //now we've got numRandomPeptides peptides chosen
                amtDB = new AmtDatabase();
                AmtRunEntry dummyRun = new AmtRunEntry(new double[]{0,1},new MS2Modification[0]);
                amtDB.addRunEntry(dummyRun);
                for (Peptide randomPeptide : randomPeptides)
                    amtDB.addObservation(new String(randomPeptide.getChars()),
                            null, .95,
                            AmtUtilities.calculateNormalizedHydrophobicity(randomPeptide),
                            dummyRun, 1, 3000);
            }
        }

        if (outFile != null && amtDB != null)
            writeAmtDatabase(amtDB, outFile);
    }



    /**
     *
     * @param amtDatabase
     * @param outAmtXmlFile
     */
    protected static void writeAmtDatabase(AmtDatabase amtDatabase,
                                           File outAmtXmlFile)
    {
        try
        {
            AmtXmlWriter amtXmlWriter = new AmtXmlWriter(amtDatabase);
            amtXmlWriter.write(outAmtXmlFile);
            ApplicationContext.infoMessage("Wrote " +
                    amtDatabase.numEntries() + " entries to amtxml file " +
                    outAmtXmlFile.getAbsolutePath());
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
            ApplicationContext.infoMessage("Error writing amt file " +
                    outAmtXmlFile.getAbsolutePath());
        }
    }


}
