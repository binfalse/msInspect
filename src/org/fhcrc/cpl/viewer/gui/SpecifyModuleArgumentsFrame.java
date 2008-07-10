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
package org.fhcrc.cpl.viewer.gui;

import org.fhcrc.cpl.viewer.commandline.CommandLineModule;
import org.fhcrc.cpl.viewer.commandline.CommandLineModuleExecutionException;
import org.fhcrc.cpl.viewer.commandline.CommandLineModuleDiscoverer;
import org.fhcrc.cpl.viewer.commandline.CommandLineModuleUtilities;
import org.fhcrc.cpl.viewer.commandline.arguments.ArgumentValidationException;
import org.fhcrc.cpl.viewer.commandline.arguments.CommandLineArgumentDefinition;
import org.fhcrc.cpl.viewer.commandline.arguments.ArgumentDefinitionFactory;
import org.fhcrc.cpl.viewer.commandline.arguments.EnumeratedValuesArgumentDefinition;
import org.fhcrc.cpl.viewer.CommandFileRunner;
import org.fhcrc.cpl.toolbox.TextProvider;
import org.fhcrc.cpl.toolbox.ApplicationContext;
import org.apache.log4j.Logger;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.prefs.Preferences;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Dialog window class for specifying command-line arguments for a given module
 */
public class SpecifyModuleArgumentsFrame extends JFrame
{
    static Logger _log = Logger.getLogger(SpecifyModuleArgumentsFrame.class);


    protected CommandLineModule module;

    protected Map<CommandLineArgumentDefinition,JComponent> argComponentMap;

    protected Preferences prefs = Preferences.userNodeForPackage(SpecifyModuleArgumentsFrame.class);

    protected static final int MAX_FIELDPANE_HEIGHT = 600;

    protected JDialog argHelpDialog;
    protected JTextArea argHelpTextArea;

    protected JDialog helpDialog;

    protected Map<String, String> moduleArgMap;


    //arbitrary
    public int width = 650;

    //will be overridden
    public int height = 300;
    public int fieldViewportHeight = 300;
    public int fieldPaneHeight = 300;

    //if true, disable the buttons related to commandline stuff, and the user's notification
    //that the command is complete
    public boolean guiOnly = false;

    public boolean done = false;
    public boolean argsSpecified = false;

    protected JButton fakeButton;




    public SpecifyModuleArgumentsFrame(CommandLineModule module, Map<String, String> moduleArgMap)
    {
        this(module, false, moduleArgMap);
    }

    /**
     * TODO: use swiXML?  Layout is variable, but some bits are constant, like the buttons
     * @param module
     */
    public SpecifyModuleArgumentsFrame(CommandLineModule module, boolean guiOnly, Map<String, String> moduleArgMap)
    {
        super(TextProvider.getText("ARGUMENTS_FOR_COMMAND_COMMAND",module.getCommandName()));

        fakeButton = new JButton("fake");

        this.module = module;
        this.guiOnly = guiOnly;
        this.moduleArgMap = moduleArgMap;


        ListenerHelper helper = new ListenerHelper(this);

        JButton buttonExecute = new JButton(TextProvider.getText("EXECUTE"));
        JButton buttonShowCommand = new JButton(TextProvider.getText("SHOW_COMMAND"));
        JButton buttonSaveCommandFile = new JButton(TextProvider.getText("SAVE_TO_FILE"));
        JButton buttonCancel = new JButton(TextProvider.getText("CANCEL"));
        JButton buttonShowHelp = new JButton(TextProvider.getText("HELP"));
        helper.addListener(buttonExecute, "buttonExecute_actionPerformed");
        helper.addListener(buttonShowCommand, "buttonShowCommand_actionPerformed");
        helper.addListener(buttonSaveCommandFile, "buttonSaveCommandFile_actionPerformed");
        helper.addListener(buttonCancel, "buttonCancel_actionPerformed");
        helper.addListener(buttonShowHelp, "buttonShowHelp_actionPerformed");

        getRootPane().setDefaultButton(buttonExecute);

        JPanel contentPanel = new JPanel();
        GridBagConstraints contentPanelGBC = new GridBagConstraints();
        contentPanelGBC.gridwidth = GridBagConstraints.REMAINDER;
        contentPanel.setLayout(new GridBagLayout());
        add(contentPanel);


        addFieldsForArguments(contentPanel, helper, moduleArgMap);

        JPanel buttonPanel = new JPanel();
        GridBagConstraints buttonPanelGBC = new GridBagConstraints();
        buttonPanelGBC.gridwidth = GridBagConstraints.REMAINDER;
        contentPanel.add(buttonPanel, buttonPanelGBC);

        GridBagConstraints buttonGBC = new GridBagConstraints();
        buttonGBC.insets = new Insets(10, 0, 10, 0);
        buttonPanel.add(buttonExecute, buttonGBC);

        if (!guiOnly)
        {
            buttonPanel.add(buttonSaveCommandFile, buttonGBC);
            buttonPanel.add(buttonShowCommand, buttonGBC);
        }

        GridBagConstraints secondToLastButtonGBC = new GridBagConstraints();
        secondToLastButtonGBC.insets = new Insets(10, 0, 10, 0);
        secondToLastButtonGBC.gridwidth = GridBagConstraints.RELATIVE;
        buttonPanel.add(buttonCancel, secondToLastButtonGBC);


        GridBagConstraints lastButtonGBC = new GridBagConstraints();
        lastButtonGBC.insets = new Insets(10, 0, 10, 0);
        lastButtonGBC.gridwidth = GridBagConstraints.REMAINDER;
        buttonPanel.add(buttonShowHelp, lastButtonGBC);




        //20 * the number of text fields, plus the height of the button area,
        //plus some padding
        height = fieldPaneHeight + 70 + 15;

        setPreferredSize(new Dimension(width+20, height));
        setSize(new Dimension(width+20, height));
        setMinimumSize(new Dimension(width+20, height));

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int centerH = screenSize.width / 2;
        int centerV = screenSize.height / 2;
        this.setLocation(centerH - width / 2, centerV - height / 2);

        try
        {
            Image iconImage = ImageIO.read(WorkbenchFrame.class.getResourceAsStream("icon.gif"));
            setIconImage(iconImage);
        }
        catch (Exception e)
        {
        }

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    }

    public void addDoneListener(ActionListener listener)
    {
        fakeButton.addActionListener(listener);
    }

    public boolean collectArguments()
    {

        setVisible(true);

        while (!done)
        {
            try
            {
                Thread.sleep(500);
            }
            catch (InterruptedException e)
            {

            }
        }

        disposeAllComponents();

        return argsSpecified;
    }

    protected void notifyDone(ActionEvent event)
    {
        ActionListener[] fakeButtonListeners = fakeButton.getActionListeners();

        if (fakeButtonListeners != null)
        {
            for (ActionListener listener : fakeButtonListeners)
                listener.actionPerformed(event);
        }
    }




    /**
     * Add fields representing each of the module's arguments
     * TODO: move field creation into the argument classes?  That would be more modular. Also more work
     * @param contentPanel
     */
    protected void addFieldsForArguments(JPanel contentPanel, ListenerHelper helper, Map<String, String> moduleArgMap)
    {
        argComponentMap = new HashMap<CommandLineArgumentDefinition,JComponent>();

        boolean firstArg = true;
        JScrollPane fieldScrollPane = new JScrollPane();
//        fieldScrollPane.setLayout(new GridBagLayout());
        fieldScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        fieldScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);          
        GridBagConstraints fieldPaneGBC = new GridBagConstraints();
        fieldPaneGBC.gridwidth=GridBagConstraints.REMAINDER;

        JPanel panelInViewport = new JPanel();
        panelInViewport.setLayout(new GridBagLayout());
        GridBagConstraints panelInViewportGBC = new GridBagConstraints();
        panelInViewportGBC.gridwidth=GridBagConstraints.REMAINDER;
        fieldScrollPane.setViewportView(panelInViewport);


        JPanel allFieldsPanel = new JPanel();
        allFieldsPanel.setLayout(new GridBagLayout());
        GridBagConstraints allFieldsPanelGBC = new GridBagConstraints();
        allFieldsPanelGBC.gridwidth=GridBagConstraints.REMAINDER;


//        contentPanel.add(allFieldsPanel, allFieldsPanelGBC);

        //For each argument, create the GUI components that will capture the value
        for (CommandLineArgumentDefinition argDef :
                module.getArgumentDefinitionsSortedForDisplay())
        {
            String labelText = argDef.getArgumentDisplayName();
            if (labelText.length() > 50)
                labelText = labelText.substring(0, 47) + "...";

            JLabel argLabel = new JLabel(labelText);
            String toolTipText = argDef.getHelpText();
            if (toolTipText == null)
                toolTipText = "";
            if (toolTipText.length() > 50)
                toolTipText = toolTipText.substring(0, 46) + "....";
            argLabel.setToolTipText(toolTipText);

            JComponent argComponent;

            String fieldValue = null;

            //if module argument map is provided, don't use prefs for defaulting
            if (moduleArgMap != null)
            {
                if (moduleArgMap.containsKey(argDef.getArgumentName()))
                {
                    fieldValue = moduleArgMap.get(argDef.getArgumentName());
                }
            }
            else
                fieldValue = prefs.get(module.getCommandName() + ":" + argDef.getArgumentName(), null);

            if (fieldValue == null || fieldValue.length() == 0)
            {
                if (argDef.hasDefaultValue())
                {
                    //TODO: this will probably bomb on some of the more complicated datatypes
                    fieldValue = argDef.getDefaultValueAsString();
                }
            }
            boolean fieldHasValue = (fieldValue != null && fieldValue.length() > 0);

            boolean shouldAddFileChooser = false;

            //treat unnamed series parameters differently -- just give a big ol' text field
            if (argDef.getArgumentName().equals(CommandLineArgumentDefinition.UNNAMED_PARAMETER_VALUE_SERIES_ARGUMENT))
            {
                JTextField argTextField = new JTextField();
                argTextField.setPreferredSize(new Dimension(220, 20));
                argTextField.setMinimumSize(new Dimension(220, 20));

                if (fieldHasValue)
                    argTextField.setText(fieldValue);
                argComponent = argTextField;
            }
            else
            {
                //special handling for each data type
                switch (argDef.getDataType())
                {
                    case ArgumentDefinitionFactory.ENUMERATED:
                        JComboBox enumeratedComboBox = new JComboBox();
                        for (String allowedValue :
                                ((EnumeratedValuesArgumentDefinition) argDef).getEnumeratedValues())
                        {
                            enumeratedComboBox.addItem(allowedValue);
                        }

                        if (fieldHasValue)
                            enumeratedComboBox.setSelectedItem(fieldValue);
                        argComponent = enumeratedComboBox;
                        break;
                    case ArgumentDefinitionFactory.BOOLEAN:
                        JComboBox booleanComboBox = new JComboBox();
                        booleanComboBox.addItem("true");
                        booleanComboBox.addItem("false");

                        if (fieldHasValue)
                            booleanComboBox.setSelectedItem(fieldValue);
                        argComponent = booleanComboBox;
                        break;
                    case ArgumentDefinitionFactory.DELTA_MASS:
                        JTextField deltaMassTextField = new JTextField();
                        deltaMassTextField.setPreferredSize(new Dimension(80, 20));
                        deltaMassTextField.setMinimumSize(new Dimension(80, 20));

                        if (fieldHasValue)
                            deltaMassTextField.setText(fieldValue);
                        argComponent = deltaMassTextField;
                        break;
                    case ArgumentDefinitionFactory.DECIMAL:
                        JTextField decimalTextField = new JTextField();
                        decimalTextField.setPreferredSize(new Dimension(70, 20));
                        decimalTextField.setMinimumSize(new Dimension(70, 20));

                        if (fieldHasValue)
                            decimalTextField.setText(fieldValue);
                        argComponent = decimalTextField;
                        break;
                    case ArgumentDefinitionFactory.INTEGER:
                        JTextField intTextField = new JTextField();
                        intTextField.setPreferredSize(new Dimension(50, 20));
                        intTextField.setMinimumSize(new Dimension(50, 20));

                        if (fieldHasValue)
                            intTextField.setText(fieldValue);
                        argComponent = intTextField;
                        break;
                    case ArgumentDefinitionFactory.FASTA_FILE:
                    case ArgumentDefinitionFactory.FEATURE_FILE:
                    case ArgumentDefinitionFactory.FILE_TO_READ:
                    case ArgumentDefinitionFactory.FILE_TO_WRITE:
                    case ArgumentDefinitionFactory.DIRECTORY_TO_READ:
                        JTextField fileTextField = new JTextField();
                        fileTextField.setPreferredSize(new Dimension(225, 20));
                        fileTextField.setMinimumSize(new Dimension(225, 20));

                        if (fieldHasValue)
                            fileTextField.setText(fieldValue);
                        argComponent = fileTextField;
                        shouldAddFileChooser = true;
                        break;
                    default:
                        JTextField argTextField = new JTextField();
                        argTextField.setPreferredSize(new Dimension(120, 20));
                        argTextField.setMinimumSize(new Dimension(120, 20));

                        if (fieldHasValue)
                            argTextField.setText(fieldValue);
                        argComponent = argTextField;
                        break;
                }
            }

            //build all the GUI components
            argComponentMap.put(argDef, argComponent);

            JPanel fieldPanel = new JPanel();
            GridBagConstraints fieldGBC = new GridBagConstraints();
            fieldGBC.gridwidth=GridBagConstraints.REMAINDER;
            fieldGBC.anchor = GridBagConstraints.LINE_START;

            JPanel labelPanel = new JPanel();
            GridBagConstraints labelPanelGBC = new GridBagConstraints();
            labelPanelGBC.gridwidth=GridBagConstraints.RELATIVE;
            labelPanelGBC.anchor = GridBagConstraints.LINE_END;

            GridBagConstraints labelGBC = new GridBagConstraints();
            labelGBC.anchor = GridBagConstraints.LINE_END;
            labelGBC.gridwidth=GridBagConstraints.RELATIVE;       

            GridBagConstraints helpGBC = new GridBagConstraints();
            helpGBC.anchor = GridBagConstraints.LINE_START;

            GridBagConstraints textFieldGBC = new GridBagConstraints();
            textFieldGBC.anchor = GridBagConstraints.LINE_START;
            if (shouldAddFileChooser)
                textFieldGBC.gridwidth=GridBagConstraints.RELATIVE;
            else
                textFieldGBC.gridwidth=GridBagConstraints.REMAINDER;

            labelGBC.insets = new Insets(0, 0, 0, 5);
            if (firstArg)
            {
                labelGBC.insets = new Insets(10, 0, 0, 5);
                textFieldGBC.insets = new Insets(10, 0, 0, 0);
                firstArg = false;
            }

            fieldPanel.add(argComponent, textFieldGBC);

            //add a file chooser that drives off of and populates the text field,
            //if this is a file data type
            if (shouldAddFileChooser)
            {
                JButton chooserButton = new JButton(TextProvider.getText("BROWSE_DOTDOTDOT"));
                GridBagConstraints buttonGBC = new GridBagConstraints();
                buttonGBC.gridwidth = GridBagConstraints.REMAINDER;
                chooserButton.setActionCommand(argDef.getArgumentName());
                helper.addListener(chooserButton, "buttonChooseFile_actionPerformed");

                fieldPanel.add(chooserButton, buttonGBC);
            }


            if (argDef.isRequired())
            {
                JLabel requiredLabel = new JLabel("*");
                requiredLabel.setForeground(Color.BLUE);
                requiredLabel.setToolTipText("This argument is required");
                requiredLabel.addMouseListener(new ArgRequiredListener());
                labelPanel.add(requiredLabel);
            }
            labelPanel.add(argLabel, labelGBC);
            //only add help link if there's help text to show
            if (argDef.getHelpText() != null && argDef.getHelpText().length() > 0)
            {
                JLabel helpLabel = new JLabel("?");
                helpLabel.setToolTipText("Click for argument help");
                helpLabel.setForeground(Color.BLUE);
                helpLabel.addMouseListener(new ArgHelpListener(argDef));
                
                labelPanel.add(helpLabel, helpGBC);
            }

            allFieldsPanel.add(labelPanel, labelPanelGBC);
            allFieldsPanel.add(fieldPanel, fieldGBC);
        }
        //20 * the number of text fields, plus the height of the button area,
        //plus some padding
        fieldViewportHeight =  33 * argComponentMap.size() + 25;
        fieldPaneHeight = Math.min(fieldViewportHeight, MAX_FIELDPANE_HEIGHT);
        fieldPaneHeight = Math.max(300, fieldPaneHeight);

        allFieldsPanel.setMinimumSize(new Dimension(width, fieldViewportHeight));
        allFieldsPanel.setPreferredSize(new Dimension(width, fieldViewportHeight));

        fieldScrollPane.setMinimumSize(new Dimension(width, fieldPaneHeight));
        fieldScrollPane.setPreferredSize(new Dimension(width, fieldPaneHeight));

        contentPanel.add(fieldScrollPane, fieldPaneGBC);
        panelInViewport.add(allFieldsPanel, allFieldsPanelGBC);

        //scroll horiz scrollbar to the right, to make sure the actual fields are fully visible
        JScrollBar scrollBar = fieldScrollPane.getHorizontalScrollBar();
        if (scrollBar != null)
            scrollBar.setValue(scrollBar.getMaximum());

    }

    protected class ArgRequiredListener implements MouseListener
    {
        public void mouseClicked(MouseEvent e)
        {
            infoMessage("This argument is required");
        }

        public void mousePressed(MouseEvent e) {}
        public void mouseReleased(MouseEvent e) {}
        public void mouseEntered(MouseEvent e) {}
        public void mouseExited(MouseEvent e) {}
    }

    protected class ArgHelpListener implements MouseListener
    {
        protected CommandLineArgumentDefinition argDef;

        public ArgHelpListener(CommandLineArgumentDefinition argDef)
        {
            this.argDef = argDef;
        }


        public void mouseClicked(MouseEvent e)
        {
            String argName = argDef.getArgumentName();


            if (argHelpDialog != null && argHelpDialog.isVisible())
            {
                //help already initialized and visible
            }
            else
            {
                argHelpTextArea = new JTextArea();
                argHelpTextArea.setEditable(false);
                argHelpTextArea.setOpaque(false);
                argHelpTextArea.setBounds(0,0,500,200);
                argHelpTextArea.setLineWrap(true);
                argHelpTextArea.setWrapStyleWord(true);

                argHelpDialog = new JDialog();
                argHelpDialog.add(argHelpTextArea);
                argHelpDialog.setVisible(true);
                Point thisLocation = getLocation();
                argHelpDialog.setLocation((int) thisLocation.getX() + 10, (int) thisLocation.getY() + 5);
                argHelpDialog.setSize(600,150);
                argHelpDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                argHelpDialog.setModal(true);
                argHelpDialog.setAlwaysOnTop(true);
            }

            argHelpTextArea.setText(argDef.getHelpText());
            argHelpDialog.setTitle("Help for argument '" + argName + "'");

            argHelpTextArea.setVisible(true);
            argHelpDialog.setVisible(true);
        }

        public void mousePressed(MouseEvent e) {}
        public void mouseReleased(MouseEvent e) {}
        public void mouseEntered(MouseEvent e) {}
        public void mouseExited(MouseEvent e) {}
    }

    protected Map<String,String> parseFieldArguments()
    {
        Map<String,String> argNameValueMap = new HashMap<String,String>();



        for (CommandLineArgumentDefinition argDef : argComponentMap.keySet())
        {
            JComponent argComponent = argComponentMap.get(argDef);

            String argValue;
            switch (argDef.getDataType())
            {
                case ArgumentDefinitionFactory.BOOLEAN:
                case ArgumentDefinitionFactory.ENUMERATED:
                    argValue = (String) ((JComboBox) argComponent).getSelectedItem();
                    break;
                default:
                    argValue = ((JTextField) argComponent).getText();
            }

            if (argValue != null && argValue.length() > 0)
            {
                if (argDef.getArgumentName().equals(
                        CommandLineArgumentDefinition.UNNAMED_PARAMETER_VALUE_SERIES_ARGUMENT))
                {
                    _log.debug("Unnamed series.  Before:\n" + argValue);
                    argValue = argValue.trim();
                    argValue = argValue.replaceAll(" ", CommandLineModule.UNNAMED_ARG_SERIES_SEPARATOR);
                    _log.debug("After:\n" + argValue);                    

                }

                argNameValueMap.put(argDef.getArgumentName(), argValue);
                prefs.put(module.getCommandName() + ":" + argDef.getArgumentName(), argValue);
            }
            else
            {
                prefs.remove(module.getCommandName() + ":" + argDef.getArgumentName());
            }
        }

        return argNameValueMap;
    }

    /**
     * Show a dialog box with help for this command
     * @param event
     */
    public void buttonShowHelp_actionPerformed(ActionEvent event)
    {
		JTextArea helpTextArea = new JTextArea();
        helpTextArea.setEditable(false);
        helpTextArea.setOpaque(false);
        helpTextArea.setText(module.getFullHelp());
        helpTextArea.setWrapStyleWord(true);
        helpTextArea.setLineWrap(true);

        final JScrollPane scrollpane = new JScrollPane(helpTextArea);
        scrollpane.setMinimumSize(new Dimension(width, 100));
        scrollpane.setBorder(BorderFactory.createCompoundBorder(
        		BorderFactory.createEmptyBorder(5,0,5,0),
        		scrollpane.getBorder()));
        scrollpane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollpane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        // Set scroll to the top (it defaults to the bottom)
        SwingUtilities.invokeLater(new Runnable()
        {
        	public void run() {
        		JScrollBar scrollBar = scrollpane.getVerticalScrollBar();
        		scrollBar.setValue(scrollBar.getMinimum());
        	}
        });


        GridBagConstraints helpGBC = new GridBagConstraints();
        helpGBC.fill = GridBagConstraints.VERTICAL;
        helpGBC.gridwidth = GridBagConstraints.REMAINDER;
        helpGBC.weighty = 1;

        helpDialog = new JDialog();
        helpDialog.setTitle("Help for command '" + module.getCommandName() + "'");
        helpDialog.add(scrollpane);


        Point thisLocation = getLocation();
        helpDialog.setLocation((int) thisLocation.getX() + 10, (int) thisLocation.getY() + 5);
        helpDialog.setSize(800,600);
        helpDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        helpDialog.setVisible(true);
        
    }

    public void buttonSaveCommandFile_actionPerformed(ActionEvent event)
    {
        WorkbenchFileChooser wfc = new WorkbenchFileChooser();
        int chooserStatus = wfc.showOpenDialog(this);
        //if user didn't hit OK, ignore
        if (chooserStatus != JFileChooser.APPROVE_OPTION)
            return;

        final File file = wfc.getSelectedFile();
        if (null != file)
        {
            Map<String,String> argNameValueMap = parseFieldArguments();
            String commandFileText =
                    CommandFileRunner.createCommandFileEntry(module.getCommandName(),
                            argNameValueMap);
            PrintWriter outPW = null;
            try
            {
                outPW = new PrintWriter(file);
                outPW.print(commandFileText);
                infoMessage(TextProvider.getText("FILE_FILENAME_SAVED",
                            file.getAbsolutePath()));
            }
            catch (FileNotFoundException e)
            {
                infoMessage(TextProvider.getText("ERROR") + ":" + e.getMessage());
            }
            finally
            {
                if (outPW != null)
                    outPW.close();
            }
        }
        notifyDone(event);
    }

    public void buttonChooseFile_actionPerformed(ActionEvent event)
    {
        String argName = event.getActionCommand();
        for (CommandLineArgumentDefinition argDef : argComponentMap.keySet())
        {
            if (argDef.getArgumentName().equals(argName))
            {
                JTextField fileTextField = (JTextField) argComponentMap.get(argDef);
                WorkbenchFileChooser wfc = new WorkbenchFileChooser();
                String currentFieldValue = fileTextField.getText();
                File directory = null;
                if (currentFieldValue != null &&
                    currentFieldValue.length() > 0)
                {
                    File currentFile = new File(currentFieldValue);
                    wfc.setSelectedFile(currentFile);
                    File selectedDir = currentFile.getParentFile();                  
                    if (selectedDir != null && selectedDir.exists())
                    {
                        directory = selectedDir;
                    }
                }
                else
                {
                    wfc.setCurrentDirectory(new File ("."));
                }
                int chooserStatus = wfc.showOpenDialog(this, directory);
                //if user didn't hit OK, ignore
                if (chooserStatus != JFileChooser.APPROVE_OPTION)
                    break;
                final File file = wfc.getSelectedFile();
                if (null != file)
                    fileTextField.setText(file.getAbsolutePath());
                break;
            }
        }
    }

    public void buttonShowCommand_actionPerformed(ActionEvent event)
    {
        Map<String,String> argNameValueMap = parseFieldArguments();

        StringBuffer commandLineCommand =
                new StringBuffer("msinspect --" + module.getCommandName());
        for (String argName : argNameValueMap.keySet())
        {
            commandLineCommand.append(" ");
            if (!argName.equals(CommandLineArgumentDefinition.UNNAMED_PARAMETER_VALUE_ARGUMENT) &&
                !argName.equals(CommandLineArgumentDefinition.UNNAMED_PARAMETER_VALUE_SERIES_ARGUMENT))
                commandLineCommand.append(argName + "=");

            commandLineCommand.append(argNameValueMap.get(argName));
        }
        System.err.println("Command:");
        System.err.println(commandLineCommand.toString());
    }

    public void buttonExecute_actionPerformed(ActionEvent event)
    {
        Map<String,String> argNameValueMap = parseFieldArguments();



        try
        {
            _log.debug("Executing action.  Arguments:");
            for (String argName : argNameValueMap.keySet())
            {
                _log.debug("\t" + argName + "=" + argNameValueMap.get(argName));
            }
            module.digestArguments(argNameValueMap);
        }
        catch (ArgumentValidationException e)
        {
            infoMessage(TextProvider.getText("FAILED_ARGUMENT_VALIDATION") + "\n" + 
                        TextProvider.getText("ERROR") + ": " + e.getMessage());
            return;
        }


        argsSpecified=true;
        done=true;

        notifyDone(event);
    }

    protected void disposeAllComponents()
    {
        if (argHelpDialog != null)
        {
            argHelpDialog.setVisible(false);
            argHelpDialog.dispose();
        }

        if (helpDialog != null)
        {
            helpDialog.setVisible(false);
            helpDialog.dispose();
        }

        this.setVisible(false);
        this.dispose();
    }

    protected void infoMessage(String message)
    {
        JOptionPane.showMessageDialog(ApplicationContext.getFrame(), message, "Information", JOptionPane.INFORMATION_MESSAGE);
    }

    protected void errorMessage(String message, Throwable t)
    {
        if (null != t)
        {
            message = message + "\n" + t.getMessage() + "\n";

            StringWriter sw = new StringWriter();
            PrintWriter w = new PrintWriter(sw);
            t.printStackTrace(w);
            w.flush();
            message += "\n";
            message += sw.toString();
        }
        JOptionPane.showMessageDialog(ApplicationContext.getFrame(), message, "Information", JOptionPane.INFORMATION_MESSAGE);
    }

    public void buttonCancel_actionPerformed(ActionEvent e)
    {
        disposeAllComponents();
        done=true;
        notifyDone(e);
    }


    /**
     * Simple dialog for choosing a command to specify arguments for
     */
    public static class ChooseCommandDialog extends JDialog
    {
        public JComboBox commandBox;
        public Map<String, CommandLineModule> moduleMap;
        public JTextArea descriptionPanel;

        public static final int height = 175;
        public static final int width = 300;

        protected boolean done = false;
        protected CommandLineModule chosenModule = null;

        public JButton buttonGo;
        public JButton buttonCancel;

        public JButton fakeButton;

        public ChooseCommandDialog()
        {
            super();

            setTitle(TextProvider.getText("CHOOSE_COMMAND"));

            JPanel contentPanel = new JPanel();
            GridBagConstraints contentPanelGBC = new GridBagConstraints();
            contentPanelGBC.gridwidth = GridBagConstraints.REMAINDER;
            contentPanel.setLayout(new GridBagLayout());
            add(contentPanel);

            setPreferredSize(new Dimension(width, height));
            setSize(new Dimension(width, height));
            setMinimumSize(new Dimension(width, height));

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int centerH = screenSize.width / 2;
            int centerV = screenSize.height / 2;
            this.setLocation(centerH - width / 2, centerV - height / 2);

            commandBox = new JComboBox();
            GridBagConstraints commandBoxGBC = new GridBagConstraints();
            commandBoxGBC.gridwidth = GridBagConstraints.REMAINDER;
            commandBoxGBC.insets = new Insets(10, 0, 10, 0);              
            contentPanel.add(commandBox, commandBoxGBC);

            moduleMap = CommandLineModuleDiscoverer.findAllCommandLineModules();
            String[] commandsArray = moduleMap.keySet().toArray(new String[moduleMap.size()]);
            Arrays.sort(commandsArray);
            for (String command : commandsArray)
            {
                commandBox.addItem(command);
            }

            fakeButton = new JButton("fake");

            buttonGo = new JButton(TextProvider.getText("OK"));
            GridBagConstraints buttonGoGBC = new GridBagConstraints();
            buttonGoGBC.insets = new Insets(0, 30, 0, 0);              
            buttonGoGBC.gridwidth = GridBagConstraints.RELATIVE;
            getRootPane().setDefaultButton(buttonGo);

            buttonCancel = new JButton(TextProvider.getText("CANCEL"));
            GridBagConstraints buttonCancelGBC = new GridBagConstraints();
            buttonCancelGBC.gridwidth = GridBagConstraints.REMAINDER;

            contentPanel.add(buttonGo, buttonGoGBC);
            contentPanel.add(buttonCancel, buttonCancelGBC);

            descriptionPanel = new JTextArea(4, 20);
//            JScrollPane scrollPane = new JScrollPane(descriptionPanel);
            descriptionPanel.setOpaque(false);
            descriptionPanel.setEditable(false);
            descriptionPanel.setLineWrap(true);
            descriptionPanel.setWrapStyleWord(true);
            GridBagConstraints descriptionGBC = new GridBagConstraints();
            descriptionGBC.gridwidth = GridBagConstraints.REMAINDER;

            CommandLineModule firstCommand = moduleMap.get(commandsArray[0]);
            descriptionPanel.setText(firstCommand.getShortDescription());

            contentPanel.add(descriptionPanel, descriptionGBC);

            ListenerHelper helper = new ListenerHelper(this);

            helper.addListener(commandBox, "commandBox_actionPerformed");
            helper.addListener(buttonGo, "buttonGo_actionPerformed");
            helper.addListener(buttonCancel, "buttonCancel_actionPerformed");

            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);      
        }

        public CommandLineModule chooseCommand()
        {
            setVisible(true);

            while (!done)
            {
                try
                {
                    Thread.sleep(500);
                }
                catch (InterruptedException e)
                {

                }
            }

            setVisible(false);
            dispose();

            return chosenModule;
        }

        public void addDoneListener(ActionListener listener)
        {
            fakeButton.addActionListener(listener);
        }

        public void commandBox_actionPerformed(ActionEvent event)
        {
        	String command = (String) commandBox.getSelectedItem();
        	CommandLineModule clm = moduleMap.get(command);
        	descriptionPanel.setText(clm.getShortDescription());
        }
        
        public void buttonGo_actionPerformed(ActionEvent event)
        {
            String command = (String) commandBox.getSelectedItem();

            chosenModule = moduleMap.get(command);
            done=true;
            notifyDone(event);

        }

        public void buttonCancel_actionPerformed(ActionEvent event)
        {
            done=true;
            notifyDone(event);
        }

        protected void notifyDone(ActionEvent event)
        {
            ActionListener[] fakeButtonListeners = fakeButton.getActionListeners();

            if (fakeButtonListeners != null)
            {
                for (ActionListener listener : fakeButtonListeners)
                    listener.actionPerformed(event);
            }
        }
    }

    /**
     * action for the menu item that kicks macro-running off
     */
    public static class RunCommandAction extends AbstractAction
    {
        protected SpecifyModuleArgumentsFrame.ChooseCommandDialog chooseCommandDialog;
        protected SpecifyModuleArgumentsFrame interactFrame;
        protected CommandLineModule module;
        public void actionPerformed(ActionEvent event)
        {
            chooseCommandDialog =
                    new SpecifyModuleArgumentsFrame.ChooseCommandDialog();
//            CommandLineModule module = chooseCommandDialog.chooseCommand();
            chooseCommandDialog.addDoneListener(new ChooseModuleListener());
            chooseCommandDialog.setVisible(true);

        }

        protected class ChooseModuleListener implements ActionListener
        {
            public void actionPerformed(ActionEvent event)
            {
                chooseCommandDialog.setVisible(false);
                module = chooseCommandDialog.chosenModule;

                chooseCommandDialog.dispose();

                if (module == null)
                    return;

                interactFrame = new SpecifyModuleArgumentsFrame(module, true, null);
                interactFrame.addDoneListener(new ExecuteModuleListener());
                interactFrame.setVisible(true);
            }
        }

        protected class ExecuteModuleListener implements ActionListener
        {
            public void actionPerformed(ActionEvent event)
            {
                interactFrame.setVisible(false);
                if (!interactFrame.argsSpecified)
                {
                    interactFrame.dispose();
                    return;
                }

                interactFrame.dispose();

                try
                {
                    module.execute();
                }
                catch (CommandLineModuleExecutionException ex)
                {
                    String message = TextProvider.getText("ERROR_RUNNING_COMMAND_COMMAND", module.getCommandName());

                    message = message + "\n" + ex.getMessage() + "\n";

                    StringWriter sw = new StringWriter();
                    PrintWriter w = new PrintWriter(sw);
                    ex.printStackTrace(w);
                    w.flush();
                    message += "\n";
                    message += sw.toString();
                    message += CommandLineModuleUtilities.createFailureReportAndPrompt(module, ex);
                    JOptionPane.showMessageDialog(ApplicationContext.getFrame(), message, "Information",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
    }
}

