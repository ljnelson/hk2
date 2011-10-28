/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.hk2.tests.basic;

import static org.glassfish.hk2.tests.basic.AssertionUtils.assertInjectedFactory;
import static org.glassfish.hk2.tests.basic.AssertionUtils.assertInjectedInstance;
import static org.glassfish.hk2.tests.basic.AssertionUtils.assertInjectedProvider;
import static org.glassfish.hk2.tests.basic.AssertionUtils.assertQualifierInjectedContent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.glassfish.hk2.BinderFactory;
import org.glassfish.hk2.ComponentException;
import org.glassfish.hk2.Factory;
import org.glassfish.hk2.HK2;
import org.glassfish.hk2.Module;
import org.glassfish.hk2.Provider;
import org.glassfish.hk2.Services;
import org.glassfish.hk2.TypeLiteral;
import org.glassfish.hk2.inject.Injector;
import org.glassfish.hk2.tests.basic.annotations.MarkerA;
import org.glassfish.hk2.tests.basic.annotations.MarkerB;
import org.glassfish.hk2.tests.basic.arbitrary.ClassX;
import org.glassfish.hk2.tests.basic.arbitrary.ConstructorQualifierInjectedClass;
import org.glassfish.hk2.tests.basic.arbitrary.CustomScopeInjectedClass;
import org.glassfish.hk2.tests.basic.arbitrary.QualifierInjectedClass;
import org.glassfish.hk2.tests.basic.contracts.ContractA;
import org.glassfish.hk2.tests.basic.contracts.ContractB;
import org.glassfish.hk2.tests.basic.contracts.FactoryProvidedContractA;
import org.glassfish.hk2.tests.basic.contracts.FactoryProvidedContractB;
import org.glassfish.hk2.tests.basic.contracts.FactoryProvidedContractC;
import org.glassfish.hk2.tests.basic.contracts.GenericContract;
import org.glassfish.hk2.tests.basic.contracts.GenericContractStringImpl;
import org.glassfish.hk2.tests.basic.contracts.InstanceBoundContract;
import org.glassfish.hk2.tests.basic.injected.ConstructorInjectedFactoryBindingTestClass;
import org.glassfish.hk2.tests.basic.injected.ConstructorInjectedInstanceBindingTestClass;
import org.glassfish.hk2.tests.basic.injected.ConstructorInjectedTypeBindingTestClass;
import org.glassfish.hk2.tests.basic.injected.FactoryProvidedContractAImpl;
import org.glassfish.hk2.tests.basic.injected.FactoryProvidedContractBFactory;
import org.glassfish.hk2.tests.basic.injected.FactoryProvidedContractBImpl;
import org.glassfish.hk2.tests.basic.injected.FactoryProvidedContractCAImpl;
import org.glassfish.hk2.tests.basic.injected.FactoryProvidedContractCBImpl;
import org.glassfish.hk2.tests.basic.injected.FactoryProvidedContractCFactory;
import org.glassfish.hk2.tests.basic.injected.FieldInjectedFactoryBindingTestClass;
import org.glassfish.hk2.tests.basic.injected.FieldInjectedInstanceBindingTestClass;
import org.glassfish.hk2.tests.basic.injected.FieldInjectedTypeBindingTestClass;
import org.glassfish.hk2.tests.basic.scopes.CustomScope;
import org.glassfish.hk2.tests.basic.services.ConstructorQualifierInjectedService;
import org.glassfish.hk2.tests.basic.services.InstanceBoundService;
import org.glassfish.hk2.tests.basic.services.QualifierInjectedService;
import org.glassfish.hk2.tests.basic.services.ServiceA;
import org.glassfish.hk2.tests.basic.services.ServiceB;
import org.glassfish.hk2.tests.basic.services.ServiceB1;
import org.glassfish.hk2.tests.basic.services.ServiceC;
import org.glassfish.hk2.tests.basic.services.ServiceD;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.jvnet.hk2.annotations.Inject;

/**
 * This is a test of basic injection features. To avoid the potential influence
 * of being in the same package (e.g. implicit access to the package-private data),
 * all classes that the test works with are placed in separate nested packages.
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
public class BasicInjectionTest {

    public static InstanceBoundContract boundContractInstance = new InstanceBoundContract() {
    };
    public static InstanceBoundService boundServiceInstance = new InstanceBoundService();

    public static class TestModule implements Module {

        @Override
        public void configure(BinderFactory binderFactory) {
            // basic, generic & qualified bindings
            binderFactory.bind(ContractA.class).to(ServiceA.class);
            binderFactory.bind(ContractB.class).to(ServiceB.class);
            binderFactory.bind(ContractB.class).annotatedWith(MarkerA.class).to(ServiceB1.class);
            binderFactory.bind().to(ServiceC.class);
            binderFactory.bind().to(ServiceD.class);
            binderFactory.bind(new TypeLiteral<GenericContract<String>>() {}).to(GenericContractStringImpl.class);
            binderFactory.bind(new TypeLiteral<GenericContract<String>>() {}).annotatedWith(MarkerA.class).toInstance(new GenericContract<String>() {

                @Override
                public String foo() {
                    return MarkerA.class.getName();
                }

            });

            // instance bindings
            binderFactory.bind(InstanceBoundContract.class).toInstance(boundContractInstance);
            binderFactory.bind().toInstance(boundServiceInstance);

            // scoped bindings
            binderFactory.bind(CustomScope.class).toInstance(new CustomScope());
            binderFactory.bind().to(CustomScopeInjectedClass.class).in(CustomScope.class);

            // factory provided bindings
            binderFactory.bind(FactoryProvidedContractA.class).toFactory(new Factory<FactoryProvidedContractA>() {

                @Override
                public FactoryProvidedContractA get() throws ComponentException {
                    return new FactoryProvidedContractAImpl();
                }
            });
            binderFactory.bind(FactoryProvidedContractB.class).toFactory(FactoryProvidedContractBFactory.class);
            binderFactory.bind(FactoryProvidedContractC.class).annotatedWith(MarkerA.class).toFactory(FactoryProvidedContractCFactory.class);
            binderFactory.bind(FactoryProvidedContractC.class).annotatedWith(MarkerB.class).toFactory(new Factory<FactoryProvidedContractC>() {

                @Override
                public FactoryProvidedContractC get() throws ComponentException {
                    return new FactoryProvidedContractCBImpl();
                }
            });

            // injected test class bindings
            binderFactory.bind().to(FieldInjectedTypeBindingTestClass.class);
            binderFactory.bind().to(ConstructorInjectedTypeBindingTestClass.class);
            binderFactory.bind().to(QualifierInjectedService.class);
            binderFactory.bind().to(ConstructorQualifierInjectedService.class);
            binderFactory.bind().to(FieldInjectedFactoryBindingTestClass.class);
            binderFactory.bind().to(ConstructorInjectedFactoryBindingTestClass.class);
        }
    }
    private static Services services;

    @BeforeClass
    public static void setup() {
        services = HK2.get().create(null, new TestModule());
    }

    @Test
    @Ignore
    public void testTypeBindingProvisioningViaServicesApi() {
        final ServiceC sc = services.forContract(ServiceC.class).get();
        assertInjectedInstance(ServiceC.class, sc);

        final ClassX cx = services.forContract(ClassX.class).get();
        assertInjectedInstance(ClassX.class, cx);

        final ContractB cb = services.forContract(ContractB.class).get();
        assertInjectedInstance(ServiceB.class, cb);
        assertInjectedInstance(ClassX.class, cb.getX());

        final ContractA ca = services.forContract(ContractA.class).get();
        assertInjectedInstance(ServiceA.class, ca);
        assertInjectedInstance(ServiceB.class, ca.getB());
        assertInjectedInstance(ClassX.class, ca.getB().getX());

        final GenericContract<String> gc = services.forContract(new TypeLiteral<GenericContract<String>>(){}).get();
        assertInjectedInstance(GenericContractStringImpl.class, gc);

        final GenericContract<String> gcm = services.forContract(new TypeLiteral<GenericContract<String>>(){}).annotatedWith(MarkerA.class).get();
        assertEquals(MarkerA.class.getName(), gcm.foo());

        final Provider<ServiceC> scp = services.forContract(ServiceC.class).getProvider();
        assertInjectedProvider(ServiceC.class, scp);

        final Provider<ClassX> cxp = services.forContract(ClassX.class).getProvider();
        assertInjectedProvider(ClassX.class, cxp);

        final Provider<ContractB> cbp = services.forContract(ContractB.class).getProvider();
        assertInjectedProvider(ServiceB.class, cbp);
        assertInjectedInstance(ClassX.class, cbp.get().getX());

        final Provider<ContractA> cap = services.forContract(ContractA.class).getProvider();
        assertInjectedProvider(ServiceA.class, cap);
        assertInjectedInstance(ServiceB.class, cap.get().getB());
        assertInjectedInstance(ClassX.class, cap.get().getB().getX());

        final Provider<GenericContract<String>> gcp = services.forContract(new TypeLiteral<GenericContract<String>>(){}).getProvider();
        assertInjectedProvider(GenericContractStringImpl.class, gcp);

        final Provider<GenericContract<String>> gcp_marker = services.forContract(new TypeLiteral<GenericContract<String>>(){}).annotatedWith(MarkerA.class).getProvider();
        assertEquals(MarkerA.class.getName(), gcp_marker.get().foo());
    }

    @Test
    @Ignore
    public void testTypeBindingInjection() {
        final FieldInjectedTypeBindingTestClass fi = services.forContract(FieldInjectedTypeBindingTestClass.class).get();
        fi.assertInjection();

        final ConstructorInjectedTypeBindingTestClass ci = services.forContract(ConstructorInjectedTypeBindingTestClass.class).get();
        ci.assertInjection();
    }

    @Test
    public void testInstanceBindingProvisioningViaServicesApi() {
        final InstanceBoundContract i1 = services.forContract(InstanceBoundContract.class).get();
        final InstanceBoundContract i2 = services.forContract(InstanceBoundContract.class).getProvider().get();
        assertSame("Provisioned contract instance not the same as the one used in instance binding definition", boundContractInstance, i1);
        assertSame("Provisioned contract instance not the same as the one used in instance binding definition", boundContractInstance, i2);

        final InstanceBoundService s1 = services.forContract(InstanceBoundService.class).get();
        final InstanceBoundService s2 = services.forContract(InstanceBoundService.class).getProvider().get();
        assertSame("Provisioned service instance not the same as the one used in instance binding definition", boundServiceInstance, s1);
        assertSame("Provisioned service instance not the same as the one used in instance binding definition", boundServiceInstance, s2);
    }

    @Test
    public void testInstanceBindingInjection() {
        final FieldInjectedInstanceBindingTestClass fi = services.forContract(FieldInjectedInstanceBindingTestClass.class).get();
        fi.assertInjection();

        final ConstructorInjectedInstanceBindingTestClass ci = services.forContract(ConstructorInjectedInstanceBindingTestClass.class).get();
        ci.assertInjection();
    }

    @Test
    public void testQualifiedInjection() {
        assertQualifierInjectedContent(services.forContract(QualifierInjectedService.class).get());
        assertQualifierInjectedContent(services.forContract(ConstructorQualifierInjectedService.class).get());
        assertQualifierInjectedContent(services.forContract(QualifierInjectedClass.class).get());
        assertQualifierInjectedContent(services.forContract(ConstructorQualifierInjectedClass.class).get());
    }

    @Test
    public void testFactoryProvidedContractProvisioningViaServicesApi() {
        // binding defined using (annonymous) factory instance
        final FactoryProvidedContractA a = services.forContract(FactoryProvidedContractA.class).get();
        assertInjectedInstance(FactoryProvidedContractAImpl.class, a);

        final Provider<FactoryProvidedContractA> pa = services.forContract(FactoryProvidedContractA.class).getProvider();
        assertInjectedProvider(FactoryProvidedContractAImpl.class, pa);

        // binding defined using factory class
        final FactoryProvidedContractB b = services.forContract(FactoryProvidedContractB.class).get();
        assertInjectedInstance(FactoryProvidedContractBImpl.class, b);

        final Provider<FactoryProvidedContractB> pb = services.forContract(FactoryProvidedContractB.class).getProvider();
        assertInjectedProvider(FactoryProvidedContractBImpl.class, pb);

        // binding defined using factory class and qualifier annotation
        final FactoryProvidedContractC c_a = services.forContract(FactoryProvidedContractC.class).annotatedWith(MarkerA.class).get();
        assertInjectedInstance(FactoryProvidedContractCAImpl.class, c_a);

        final Provider<FactoryProvidedContractC> pc_a = services.forContract(FactoryProvidedContractC.class).annotatedWith(MarkerA.class).getProvider();
        assertInjectedProvider(FactoryProvidedContractCAImpl.class, pc_a);

        // binding defined using factory instance and qualifier annotation
        final FactoryProvidedContractC c_b = services.forContract(FactoryProvidedContractC.class).annotatedWith(MarkerB.class).get();
        assertInjectedInstance(FactoryProvidedContractCBImpl.class, c_b);

        final Provider<FactoryProvidedContractC> pc_b = services.forContract(FactoryProvidedContractC.class).annotatedWith(MarkerB.class).getProvider();
        assertInjectedProvider(FactoryProvidedContractCBImpl.class, pc_b);

        // verifying null is returned for non-annotated binding that was not defined
        final FactoryProvidedContractC c_default = services.forContract(FactoryProvidedContractC.class).get();
        assertNull("No binding defined for the non-annotated contract. Provisioned instance should be null.", c_default);

        final Provider<FactoryProvidedContractC> pc_default = services.forContract(FactoryProvidedContractC.class).getProvider();
        assertTrue("No binding defined for the non-annotated contract. Provider or returned instance should be null.", pc_default == null || pc_default.get() == null);
    }

    @Test
    public void testFactoryProvidedContractInjection() {
        final FieldInjectedFactoryBindingTestClass fi = services.forContract(FieldInjectedFactoryBindingTestClass.class).get();
        fi.assertInjection();

        final ConstructorInjectedFactoryBindingTestClass ci = services.forContract(ConstructorInjectedFactoryBindingTestClass.class).get();
        ci.assertInjection();
    }

    @Test
    public void testTypeLiteralBoundToFactory() {
        final List<String> expected = Arrays.asList(new String[]{"test"});
        Services s = HK2.get().create(null, new Module() {

            @Override
            public void configure(BinderFactory binderFactory) {
                binderFactory.bind(new TypeLiteral<List<String>>() {
                }).toFactory(new Factory<List<String>>() {

                    @Override
                    public List<String> get() throws ComponentException {
                        return expected;
                    }
                });
            }
        });

        List<String> result;
        result = s.forContract(new TypeLiteral<List<String>>() {
        }).get();
        assertEquals(expected, result);
    }

    @Test
    public void testScopes() {
        final CustomScope customScope = services.forContract(CustomScope.class).get();

        CustomScopeInjectedClass customScopeInjectedClass;

        customScope.enter();
        customScopeInjectedClass = services.forContract(CustomScopeInjectedClass.class).get();
        assertNotNull("Instance not provisioned", customScopeInjectedClass);
        customScope.leave();

        try {
            customScopeInjectedClass = services.forContract(CustomScopeInjectedClass.class).get();
        } catch (IllegalStateException ex) {
            assertEquals(ex.getMessage(), CustomScope.OUT_OF_SCOPE_MESSAGE);
            return;
        }

        fail("IllegalStateException expected to be raised when trying to access a custom-scoped biding outside of the scope.");
    }

    @Test
    @Ignore
    public void testInjector() {
        Injector injector = services.forContract(Injector.class).get();
        FieldInjectedTypeBindingTestClass fi = new FieldInjectedTypeBindingTestClass();
        injector.inject(fi);
        fi.assertInjection();

        ConstructorInjectedTypeBindingTestClass ci = injector.inject(ConstructorInjectedTypeBindingTestClass.class);
        ci.assertInjection();
    }

    @Test
    public void testConstructorBasedInjectionOnNonStaticInnerClass() {
        Injector injector = services.forContract(Injector.class).get();
        class TestClass {

            final ContractA ca;
            final Factory<ContractA> cap;

            public TestClass(
                    @Inject ContractA ca,
                    @Inject Factory<ContractA> cap) {
                this.ca = ca;
                this.cap = cap;
            }
        }
        TestClass ci = injector.inject(TestClass.class);

        assertInjectedInstance(ServiceA.class, ci.ca);
        assertInjectedInstance(ServiceB.class, ci.ca.getB());
        assertInjectedInstance(ClassX.class, ci.ca.getB().getX());

        assertInjectedFactory(ServiceA.class, ci.cap);
        assertInjectedInstance(ServiceB.class, ci.cap.get().getB());
        assertInjectedInstance(ClassX.class, ci.cap.get().getB().getX());
    }
}
