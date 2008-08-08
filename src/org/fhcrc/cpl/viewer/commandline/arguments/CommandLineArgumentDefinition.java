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

package org.fhcrc.cpl.viewer.commandline.arguments;

import org.fhcrc.cpl.viewer.commandline.arguments.ArgumentValidationException;

/**
 * This interface allows for communication between msInspect and individual CommandLineModules about
 * required and optional arguments.
 *
 * Different types of arguments are validated/converted by different implementing classes.
 *
 * We recommend that these classes also extend BaseArgumentDefinitionImpl, but that is not required.
 */
public interface CommandLineArgumentDefinition
{
    //This String, passed as an argument name, is an indicator that this command allows
    //one argument specified without a parameter name.  Only one such argument is allowed
    //per command module
    public static final String UNNAMED_PARAMETER_VALUE_ARGUMENT = "unnamed_parameter_value_argument";

    //This String, passed as an argument name, is an indicator that this command allows
    //multiple arguments specified without a parameter name.  Conflicts with the one above... you can't
    //have both.
    //By convention, arguments specified for this parameter will be assembled into one big String,
    //with the individual args separated by an '='
    public static final String UNNAMED_PARAMETER_VALUE_SERIES_ARGUMENT = "unnamed_parameter_value_series_argument";

    /**
     *
     * @return the name of the argument.  When the user enters arguments, their names are checked against
     * this value, case-insensitively
     */
    public String getArgumentName();

    /**
     *
     * @return the name of the argument for display purposes.  For named arguments, this should really be
     * the same as getArgumentName().  But for unnamed arguments or unnamed series arguments, this gives the
     * developer a chance to use a different name in the help
     */
    public String getArgumentDisplayName();

    /**
     *
     * set the name of the argument for display purposes.  For named arguments, this should really be
     * the same as getArgumentName().  But for unnamed arguments or unnamed series arguments, this gives the
     * developer a chance to use a different name in the help
     */
    public void setArgumentDisplayName(String displayName);

    /**
     *
     * @return help text for specifying this argument, or null if not specified
     */
    public String getHelpText();

    /**
     *
     * @return help text for specifying this argument, or null if not specified
     */
    public void setHelpText(String helpText);

    /**
     * Validate the argument value and convert it to an Object of the appropriate type
     * @param argumentValue
     * @return true if it validates, false if not
     */
    public Object convertArgumentValue(String argumentValue)
            throws ArgumentValidationException;

    /**
     * @return whether this argument is required
     */
    public boolean isRequired();

    /**
     * Declare whether this is a required argument.  This is a convenience method
     * @param required
     */
    public void setRequired(boolean required);

    /**
     * Return a String that will be used as a generic example for the value to be used
     * for this argument, in auto-generated usage
     * @return
     */
    public String getValueDescriptor();

    /**
     * Get the default value for this argument, or null if there is none.  However, since
     * null is a valid default in some cases, hasDefaultValue() should be used to determine
     * whether there is a useful default value
     * @return
     */
    public Object getDefaultValue();

    /**
     * Get the default value, as a String, for the help text.  In most cases, this will
     * be equivalent to getDefaultValue().toString();
     * @return
     */
    public String getDefaultValueAsString();    

    /**
     * Does this argument definition have a default value?
     * @return
     */
    public boolean hasDefaultValue();

    /**
     * Set the default value for this definition
     */
    public void setDefaultValue(Object defaultValue);

    public int getDataType();

    /**
     * Return a String representing this value
     * @param argValue
     * @return
     */
    public String valueToString(Object argValue);

    /**
     * Is this argument "advanced"?  That is, can a basic user of the module safely ignore it?
     * @return
     */
    public boolean isAdvanced();

    public void setAdvanced(boolean advanced);
}
