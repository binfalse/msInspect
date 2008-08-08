#!/bin/bash

ROOT=${MSINSPECT_HOME:-.}
BUILD=$ROOT/build
LIB=$ROOT/lib
LIBDB=$LIB/database
SIGNED=$ROOT/mzxml-signed
HEAPSIZE=1524M

DBJARS=$LIBDB/antlr-2.7.6.jar:$LIBDB/asm-attrs.jar:$LIBDB/asm.jar:$LIBDB/cglib-2.1.3.jar:$LIBDB/dom4j-1.6.1.jar:$LIBDB/hibernate3.jar:$LIBDB/hsqldb.jar:$LIBDB/jta.jar


java -client -Xmx$HEAPSIZE -classpath $BUILD/classes:$BUILD/lib/msInspectSchemas.jar:$BUILD/lib/swixml-1.5.jar:$LIB/Jama-1.0.2.jar:$LIB/commons-beanutils-1.7.jar:$LIB/commons-collections-3.2.jar:$LIB/commons-lang-2.3.jar:$LIB/commons-logging.jar:$LIB/j3dcore.jar:$LIB/j3dutils.jar:$LIB/jai_codec.jar:$LIB/jai_core.jar:$LIB/jcommon-1.0.0.jar:$LIB/jdom.jar:$LIB/jfreechart-1.0.6.jar:$LIB/junit-4.1.jar:$LIB/log4j-1.2.8.jar:$LIB/ui.jar:$LIB/vecmath.jar:$LIB/swixml-1.5.jar:$SIGNED/xercesImpl.jar:$LIB/stax-api-1.0.1.jar:$LIB/xml-apis-1.2.01.jar:$LIB/wstx-lgpl-3.2.1.jar:$LIB/xbean-2.3.0.jar:$LIB/mail.jar:$LIB/activation.jar:$LIB/commons-discovery-0.2.jar:$LIB/apml_parser_v2.jar:$DBJARS org.fhcrc.cpl.viewer.Application $* 

