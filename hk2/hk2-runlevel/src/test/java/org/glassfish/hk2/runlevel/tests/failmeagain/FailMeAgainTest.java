/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.hk2.runlevel.tests.failmeagain;

import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.runlevel.RunLevelController;
import org.glassfish.hk2.runlevel.tests.utilities.Utilities;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * @author jwells
 *
 */
public class FailMeAgainTest {
    /**
     * Dummy test
     */
    @Test
    public void testDummy() {
        
    }
    
    /**
     * Tests that the grumpy service is only invoked once
     */
    @Test @Ignore
    public void testGrumpyOnlyCalledOnce() {
        ServiceLocator locator = Utilities.getServiceLocator(
                DependsOnGrumpyOne.class,
                DependsOnGrumpyTwo.class,
                GrumpyService.class);
        
        RunLevelController controller = locator.getService(RunLevelController.class);
        
        controller.setMaximumUseableThreads(2);
        
        for (int lcv = 0; lcv < 1000; lcv++) {
            GrumpyService.reset();
            
            try {
                controller.proceedTo(5);
                Assert.fail("Should have failed, grumpy throws");
            }
            catch (MultiException re) {
                // success
            }
            
            Assert.assertEquals("Failed on iteration " + lcv, 1, GrumpyService.getCalled());
            
            controller.proceedTo(4);
        }
        
        
    }

}
