/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.hk2.xml.internal;

import java.lang.reflect.Constructor;

import javax.xml.bind.annotation.XmlRootElement;

import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.configuration.hub.api.WriteableBeanDatabase;
import org.glassfish.hk2.configuration.hub.api.WriteableType;
import org.glassfish.hk2.utilities.AbstractActiveDescriptor;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.hk2.utilities.reflection.ReflectionHelper;
import org.glassfish.hk2.xml.jaxb.internal.BaseHK2JAXBBean;

/**
 * @author jwells
 *
 */
public class Utilities {
    /** Separator for instance names */
    public final static char INSTANCE_PATH_SEPARATOR = '.';
    
    /* package */ static String convertXmlRootElementName(XmlRootElement root, Class<?> clazz) {
        if (!"##default".equals(root.name())) return root.name();
        
        String simpleName = clazz.getSimpleName();
        
        char asChars[] = simpleName.toCharArray();
        StringBuffer sb = new StringBuffer();
        
        boolean firstChar = true;
        boolean lastCharWasCapital = false;
        for (char asChar : asChars) {
            if (firstChar) {
                firstChar = false;
                if (Character.isUpperCase(asChar)) {
                    lastCharWasCapital = true;
                    sb.append(Character.toLowerCase(asChar));
                }
                else {
                    lastCharWasCapital = false;
                    sb.append(asChar);
                }
            }
            else {
                if (Character.isUpperCase(asChar)) {
                    if (!lastCharWasCapital) {
                        sb.append('-');
                    }
                    
                    sb.append(Character.toLowerCase(asChar));
                    
                    lastCharWasCapital = true;
                }
                else {
                    sb.append(asChar);
                    
                    lastCharWasCapital = false;
                }
            }
        }
        
        return sb.toString();
    }
    
    public static BaseHK2JAXBBean createBean(Class<?> implClass) {
        try {
            Constructor<?> noArgsConstructor = implClass.getConstructor();
    
            return (BaseHK2JAXBBean) ReflectionHelper.makeMe(noArgsConstructor, new Object[0], false);
        }
        catch (RuntimeException re) {
            throw re;
        }
        catch (Throwable th) {
            throw new RuntimeException(th);
        }
    }
    
    private static String getKeySegment(BaseHK2JAXBBean bean) {
        String baseKeySegment = bean._getKeyValue();
        if (baseKeySegment == null) {
            baseKeySegment = bean._getSelfXmlTag();
        }
        
        return baseKeySegment;
    }
    
    /**
     * Creates an instance name by traveling up the parent chain.  The
     * parent chain must therefor already be correctly setup
     * 
     * @param bean The non-null bean from where to get the instancename
     * @return A unique instance name.  The combination of the xml path
     * and the instance name should uniquely identify the location of
     * any node in a single tree
     */
    public static String createInstanceName(BaseHK2JAXBBean bean) {
        if (bean._getParent() == null) {
            return getKeySegment(bean);
        }
        
        return createInstanceName((BaseHK2JAXBBean) bean._getParent()) + INSTANCE_PATH_SEPARATOR + getKeySegment(bean);
    }
    
    public static void advertise(WriteableBeanDatabase wbd, DynamicConfiguration config, BaseHK2JAXBBean bean) {
        if (config != null) {
            AbstractActiveDescriptor<?> cDesc = BuilderHelper.createConstantDescriptor(bean);
            if (bean._getKeyValue() != null) {
                cDesc.setName(bean._getKeyValue());
            }
            config.addActiveDescriptor(cDesc);
        }
        
        if (wbd != null) {
            WriteableType wt = wbd.findOrAddWriteableType(bean._getXmlPath());
            wt.addInstance(bean._getInstanceName(), bean._getBeanLikeMap());
        }
    }
    
    /**
     * Converts a getter name to a setter name (works with
     * both IS getters and GET getters)
     * 
     * @param getterName Non-null getter name starting with is or get
     * @return The corresponding setter name
     */
    public static String convertToSetter(String getterName) {
        if (getterName.startsWith(JAUtilities.IS)) {
            return JAUtilities.SET + getterName.substring(JAUtilities.IS.length());
        }
        
        if (!getterName.startsWith(JAUtilities.GET)) {
            throw new IllegalArgumentException("Unknown getter format: " + getterName);
        }
        
        return JAUtilities.SET + getterName.substring(JAUtilities.GET.length());
    }
    
    /* package */ static String getCompilableClass(Class<?> clazz) {
        int depth = 0;
        while (clazz.isArray()) {
            depth++;
            clazz = clazz.getComponentType();
        }
        
        StringBuffer sb = new StringBuffer(clazz.getName());
        for (int lcv = 0; lcv < depth; lcv++) {
            sb.append("[]");
        }
        
        return sb.toString();
    }
}
