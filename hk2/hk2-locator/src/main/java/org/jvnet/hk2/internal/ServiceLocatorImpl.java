/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
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
package org.jvnet.hk2.internal;

import org.glassfish.hk2.utilities.cache.Cache;
import org.glassfish.hk2.utilities.cache.Computable;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.ClassAnalyzer;
import org.glassfish.hk2.api.Context;
import org.glassfish.hk2.api.Descriptor;
import org.glassfish.hk2.api.DescriptorVisibility;
import org.glassfish.hk2.api.DynamicConfigurationListener;
import org.glassfish.hk2.api.ErrorService;
import org.glassfish.hk2.api.ErrorType;
import org.glassfish.hk2.api.Filter;
import org.glassfish.hk2.api.HK2Loader;
import org.glassfish.hk2.api.IndexedFilter;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.InstanceLifecycleListener;
import org.glassfish.hk2.api.InterceptionService;
import org.glassfish.hk2.api.JustInTimeInjectionResolver;
import org.glassfish.hk2.api.Operation;
import org.glassfish.hk2.api.InjectionResolver;
import org.glassfish.hk2.api.IterableProvider;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.PreDestroy;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.api.ServiceLocatorState;
import org.glassfish.hk2.api.Unqualified;
import org.glassfish.hk2.api.ValidationService;
import org.glassfish.hk2.api.Validator;
import org.glassfish.hk2.api.messaging.Topic;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.hk2.utilities.InjecteeImpl;
import org.glassfish.hk2.utilities.cache.CacheKeyFilter;
import org.glassfish.hk2.utilities.cache.HybridCacheEntry;
import org.glassfish.hk2.utilities.cache.LRUHybridCache;
import org.glassfish.hk2.utilities.reflection.Logger;
import org.glassfish.hk2.utilities.reflection.ParameterizedTypeImpl;
import org.glassfish.hk2.utilities.reflection.ReflectionHelper;

/**
 * @author jwells
 *
 */
public class ServiceLocatorImpl implements ServiceLocator {
    private final static String BIND_TRACING_PATTERN_PROPERTY = "org.jvnet.hk2.properties.bind.tracing.pattern";
    private final static String BIND_TRACING_PATTERN;
    private final static String BIND_TRACING_STACKS_PROPERTY = "org.jvnet.hk2.properties.bind.tracing.stacks";
    private final static boolean BIND_TRACING_STACKS;
    static {
        BIND_TRACING_PATTERN = AccessController.doPrivileged(new PrivilegedAction<String>() {

            @Override
            public String run() {
                return System.getProperty(BIND_TRACING_PATTERN_PROPERTY);
            }

        });

        BIND_TRACING_STACKS = AccessController.doPrivileged(new PrivilegedAction<Boolean>() {

            @Override
            public Boolean run() {
                return Boolean.parseBoolean(System.getProperty(BIND_TRACING_STACKS_PROPERTY, "false"));
            }

        });

        if (BIND_TRACING_PATTERN != null) {
            Logger.getLogger().debug("HK2 will trace binds and unbinds of " + BIND_TRACING_PATTERN +
                    " with stacks " + BIND_TRACING_STACKS);
        }
    }

    private final static int CACHE_SIZE = 20000;
    private final static Object sLock = new Object();
    private static long currentLocatorId = 0L;

    /* package */ final static DescriptorComparator DESCRIPTOR_COMPARATOR = new DescriptorComparator();
    private final static ServiceHandleComparator HANDLE_COMPARATOR = new ServiceHandleComparator();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final WriteLock wLock = readWriteLock.writeLock();
    private final ReadLock rLock = readWriteLock.readLock();
    private AtomicLong nextServiceId = new AtomicLong();
    private final String locatorName;
    private final long id;
    private final ServiceLocatorImpl parent;
    private volatile boolean neutralContextClassLoader = true;

    private final IndexedListData allDescriptors = new IndexedListData();
    private final HashMap<String, IndexedListData> descriptorsByAdvertisedContract =
            new HashMap<String, IndexedListData>();
    private final HashMap<String, IndexedListData> descriptorsByName =
            new HashMap<String, IndexedListData>();
    private final Context<Singleton> singletonContext = new SingletonContext(this);
    private final Context<PerLookup> perLookupContext = new PerLookupContext();
    private final LinkedHashSet<ValidationService> allValidators =
            new LinkedHashSet<ValidationService>();
    private final LinkedList<ErrorService> errorHandlers =
            new LinkedList<ErrorService>();
    private final LinkedList<ServiceHandle<?>> configListeners =
            new LinkedList<ServiceHandle<?>>();
    
    private volatile boolean hasInterceptionServices = false;
    private final LinkedList<InterceptionService> interceptionServices =
            new LinkedList<InterceptionService>();

    private final Cache<Class<? extends Annotation>, Context<?>> contextCache = new Cache<Class<? extends Annotation>, Context<?>>(new Computable<Class<? extends Annotation>, Context<?>>() {

        @Override
        public Context<?> compute(Class<? extends Annotation> a) {
            return _resolveContext(a);
        }
    });
    private final Map<ServiceLocatorImpl, ServiceLocatorImpl> children =
            new WeakHashMap<ServiceLocatorImpl, ServiceLocatorImpl>(); // Must be Weak for throw away children

    private final Object classAnalyzerLock = new Object();
    private final HashMap<String, ClassAnalyzer> classAnalyzers =
            new HashMap<String, ClassAnalyzer>();
    private String defaultClassAnalyzer = ClassAnalyzer.DEFAULT_IMPLEMENTATION_NAME;

    private ConcurrentHashMap<Class<? extends Annotation>, InjectionResolver<?>> allResolvers =
            new ConcurrentHashMap<Class<? extends Annotation>, InjectionResolver<?>>();
    private final Cache<Injectee, InjectionResolver<?>> injecteeToResolverCache = new Cache<Injectee, InjectionResolver<?>>(new Computable<Injectee, InjectionResolver<?>>() {

        @Override
        public InjectionResolver<?> compute(Injectee key) {
            return Utilities.getInjectionResolver(getMe(), key);
        }
        
    });

    private ServiceLocatorState state = ServiceLocatorState.RUNNING;

    private static long getAndIncrementLocatorId() {
        synchronized (sLock) {
            return currentLocatorId++;
        }
    }

    /**
     * Called by the Generator, and hence must be a public method
     *
     * @param name The name of this locator
     * @param parent The parent of this locator (may be null)
     */
    public ServiceLocatorImpl(String name, ServiceLocatorImpl parent) {
        locatorName = name;
        this.parent = parent;
        if (parent != null) {
            parent.addChild(this);
        }

        id = getAndIncrementLocatorId();

        Logger.getLogger().debug("Created ServiceLocator " + this);
    }

    /**
     * Must be called under lock
     *
     * @param descriptor The descriptor to validate
     * @param onBehalfOf The fella who is being validated (or null)
     * @return true if every validator returned true
     */
    private boolean validate(SystemDescriptor<?> descriptor, Injectee onBehalfOf, Filter filter) {
        for (ValidationService vs : getAllValidators()) {
            if (!descriptor.isValidating(vs)) continue;

            if (!vs.getValidator().validate(new ValidationInformationImpl(
                    Operation.LOOKUP, descriptor, onBehalfOf, filter))) {
                return false;
            }
        }

        return true;
    }

    private List<SystemDescriptor<?>> getDescriptors(Filter filter,
            Injectee onBehalfOf,
            boolean getParents,
            boolean doValidation,
            boolean getLocals) {
        if (filter == null) throw new IllegalArgumentException("filter is null");

        LinkedList<SystemDescriptor<?>> retVal;
        rLock.lock();
        try {
            Collection<SystemDescriptor<?>> sortMeOut;
            if (filter instanceof IndexedFilter) {
                IndexedFilter df = (IndexedFilter) filter;

                if (df.getName() != null) {
                    Collection<SystemDescriptor<?>> scopedByName;

                    String name = df.getName();

                    IndexedListData ild = descriptorsByName.get(name);
                    scopedByName = (ild == null) ? null : ild.getSortedList();
                    if (scopedByName == null) {
                        scopedByName = Collections.emptyList();
                    }

                    if (df.getAdvertisedContract() != null) {
                        sortMeOut = new LinkedList<SystemDescriptor<?>>();

                        for (SystemDescriptor<?> candidate : scopedByName) {
                            if (candidate.getAdvertisedContracts().contains(df.getAdvertisedContract())) {
                                sortMeOut.add(candidate);
                            }
                        }
                    }
                    else {
                        sortMeOut = scopedByName;
                    }
                }
                else if (df.getAdvertisedContract() != null) {
                    String advertisedContract = df.getAdvertisedContract();

                    IndexedListData ild = descriptorsByAdvertisedContract.get(advertisedContract);
                    sortMeOut = (ild == null) ? null : ild.getSortedList();
                    if (sortMeOut == null) {
                        sortMeOut = Collections.emptyList();

                    }
                }
                else {
                    sortMeOut = allDescriptors.getSortedList();
                }
            }
            else {
                sortMeOut = allDescriptors.getSortedList();
            }

            retVal = new LinkedList<SystemDescriptor<?>>();

            for (SystemDescriptor<?> candidate : sortMeOut) {
                if (!getLocals && DescriptorVisibility.LOCAL.equals(candidate.getDescriptorVisibility())) {
                    continue;
                }

                if (doValidation && !validate(candidate, onBehalfOf, filter)) continue;

                if (filter.matches(candidate)) {
                    retVal.add(candidate);
                }
            }
        } finally {
            rLock.unlock();
        }

        // Must be done outside of lock, or there can be a deadlock between child and parent
        if (getParents && parent != null) {
            TreeSet<SystemDescriptor<?>> sorter = new TreeSet<SystemDescriptor<?>>(DESCRIPTOR_COMPARATOR);

            sorter.addAll(retVal);
            sorter.addAll(parent.getDescriptors(filter, onBehalfOf, getParents, doValidation, false));

            retVal.clear();

            retVal.addAll(sorter);
        }

        return retVal;
    }

    private List<ActiveDescriptor<?>> protectedGetDescriptors(final Filter filter) {
        return AccessController.doPrivileged(new PrivilegedAction<List<ActiveDescriptor<?>>>() {

            @Override
            public List<ActiveDescriptor<?>> run() {
                return getDescriptors(filter);
            }

        });

    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getDescriptors(org.glassfish.hk2.api.Filter)
     */
    @Override
    public List<ActiveDescriptor<?>> getDescriptors(Filter filter) {
        checkState();

        return Utilities.cast(getDescriptors(filter, null, true, true, true));
    }

    @Override
    public ActiveDescriptor<?> getBestDescriptor(Filter filter) {
        if (filter == null) throw new IllegalArgumentException("filter is null");
        checkState();

        List<ActiveDescriptor<?>> sorted = getDescriptors(filter);

        return Utilities.getFirstThingInList(sorted);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#reifyDescriptor(org.glassfish.hk2.api.Descriptor)
     */
    @Override
    public ActiveDescriptor<?> reifyDescriptor(Descriptor descriptor, Injectee injectee)
            throws MultiException {
        checkState();
        if (descriptor == null) throw new IllegalArgumentException();

        if (!(descriptor instanceof ActiveDescriptor)) {
            SystemDescriptor<?> sd = new SystemDescriptor<Object>(descriptor, true, this, getNextServiceId());

            Class<?> implClass = loadClass(descriptor, injectee);

            Collector collector = new Collector();
            sd.reify(implClass, collector);

            collector.throwIfErrors();

            return sd;
        }

        // Descriptor is an active descriptor
        ActiveDescriptor<?> active = (ActiveDescriptor<?>) descriptor;
        if (active.isReified()) return active;

        SystemDescriptor<?> sd;
        if (active instanceof SystemDescriptor) {
            sd = (SystemDescriptor<?>) active;
        }
        else {
            sd = new SystemDescriptor<Object>(descriptor, true, this, getNextServiceId());
        }

        Class<?> implClass = sd.getPreAnalyzedClass();
        if (implClass == null) {
            implClass = loadClass(descriptor, injectee);
        }

        Collector collector = new Collector();

        sd.reify(implClass, collector);

        collector.throwIfErrors();

        return sd;
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#reifyDescriptor(org.glassfish.hk2.api.Descriptor)
     */
    @Override
    public ActiveDescriptor<?> reifyDescriptor(Descriptor descriptor)
            throws MultiException {
        checkState();
        return reifyDescriptor(descriptor, null);
    }

    private ActiveDescriptor<?> secondChanceResolve(Injectee injectee) {
        // OK, lets do the second chance protocol
        Collector collector = new Collector();

        List<ServiceHandle<JustInTimeInjectionResolver>> jitResolvers =
                Utilities.<List<ServiceHandle<JustInTimeInjectionResolver>>>cast(
                getAllServiceHandles(JustInTimeInjectionResolver.class));

        try {
            boolean modified = false;
            boolean aJITFailed = false;
            for (ServiceHandle<JustInTimeInjectionResolver> handle : jitResolvers) {
                if ((injectee.getInjecteeClass() != null) && (
                        injectee.getInjecteeClass().getName().equals(
                        handle.getActiveDescriptor().getImplementation()))) {
                    // Do not self second-chance
                    continue;
                }

                JustInTimeInjectionResolver jitResolver;
                try {
                    jitResolver = handle.getService();
                }
                catch (MultiException me) {
                    // We just ignore this for now, it may be resolvable later
                    Logger.getLogger().debug(handle.toString(), "secondChanceResolver", me);
                    continue;
                }

                boolean jitModified = false;
                try {
                    jitModified = jitResolver.justInTimeResolution(injectee);
                }
                catch (Throwable th) {
                    collector.addThrowable(th);
                    aJITFailed = true;
                }

                modified = jitModified || modified;
            }

            if (aJITFailed) {
                collector.throwIfErrors();
            }

            if (!modified) {
                return null;
            }

            // Try again
            return internalGetInjecteeDescriptor(injectee, false);
        }
        finally {
            for (ServiceHandle<JustInTimeInjectionResolver> jitResolver : jitResolvers) {
                if (jitResolver.getActiveDescriptor().getScope() == null ||
                        PerLookup.class.getName().equals(jitResolver.getActiveDescriptor().getScope())) {
                    // Destroy any per-lookup JIT resolver
                    jitResolver.destroy();
                }
            }
        }
    }

    private ActiveDescriptor<?> internalGetInjecteeDescriptor(Injectee injectee, boolean firstTime) {
        if (injectee == null) throw new IllegalArgumentException();
        checkState();

        Type requiredType = injectee.getRequiredType();
        Class<?> rawType = ReflectionHelper.getRawClass(requiredType);
        if (rawType == null) {
            throw new MultiException(new IllegalArgumentException(
                    "Invalid injectee with required type of " + injectee.getRequiredType() + " passed to getInjecteeDescriptor"));
        }

        if (Provider.class.equals(rawType) || IterableProvider.class.equals(rawType) ) {
            IterableProviderImpl<?> value = new IterableProviderImpl<Object>(this,
                    (Utilities.getFirstTypeArgument(requiredType)),
                    injectee.getRequiredQualifiers(),
                    injectee.getUnqualified(),
                    injectee);

            return new ConstantActiveDescriptor<Object>(value, id);
        }
        
        if (Topic.class.equals(rawType)) {
            TopicImpl<?> value = new TopicImpl<Object>(this,
                    Utilities.getFirstTypeArgument(requiredType),
                    injectee.getRequiredQualifiers());
            
            return new ConstantActiveDescriptor<Object>(value, id);
        }

        Set<Annotation> qualifiersAsSet = injectee.getRequiredQualifiers();
        String name = ReflectionHelper.getNameFromAllQualifiers(qualifiersAsSet, injectee.getParent());

        Annotation qualifiers[] = qualifiersAsSet.toArray(new Annotation[qualifiersAsSet.size()]);

        ActiveDescriptor<?> retVal = internalGetDescriptor(injectee, requiredType, name, injectee.getUnqualified(), qualifiers);
        if (retVal == null && firstTime) {
            return secondChanceResolve(injectee);
        }
        return retVal;

    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getInjecteeDescriptor(org.glassfish.hk2.api.Injectee)
     */
    @Override
    public ActiveDescriptor<?> getInjecteeDescriptor(Injectee injectee)
            throws MultiException {
        return internalGetInjecteeDescriptor(injectee, true);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getServiceHandle(org.glassfish.hk2.api.ActiveDescriptor)
     */
    @Override
    public <T> ServiceHandle<T> getServiceHandle(
            ActiveDescriptor<T> activeDescriptor,
            Injectee injectee) throws MultiException {
        return getServiceHandleImpl(activeDescriptor, injectee);
    }

    private <T> ServiceHandleImpl<T> getServiceHandleImpl(
            ActiveDescriptor<T> activeDescriptor,
            Injectee injectee) throws MultiException {
        if (activeDescriptor == null) throw new IllegalArgumentException();
        checkState();

        return new ServiceHandleImpl<T>(this, activeDescriptor, injectee);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getServiceHandle(org.glassfish.hk2.api.ActiveDescriptor)
     */
    @Override
    public <T> ServiceHandle<T> getServiceHandle(
            ActiveDescriptor<T> activeDescriptor) throws MultiException {
        return internalGetServiceHandle(activeDescriptor, null);
    }

    private <T> ServiceHandleImpl<T> internalGetServiceHandle(
            ActiveDescriptor<T> activeDescriptor,
            Type requestedType) {
        if (activeDescriptor == null) throw new IllegalArgumentException();
        checkState();

        if (requestedType == null) {
            return getServiceHandleImpl(activeDescriptor, null);
        }

        return getServiceHandleImpl(activeDescriptor, new InjecteeImpl(requestedType));
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getService(org.glassfish.hk2.api.ActiveDescriptor, org.glassfish.hk2.api.ServiceHandle)
     */
    @Override @Deprecated
    public <T> T getService(ActiveDescriptor<T> activeDescriptor,
            ServiceHandle<?> root) throws MultiException {
        return getService(activeDescriptor, root, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getService(ActiveDescriptor<T> activeDescriptor,
            ServiceHandle<?> root,
            Injectee originalRequest) throws MultiException {
        checkState();

        Type contractOrImpl = (originalRequest == null) ? null : originalRequest.getRequiredType();
        Class<?> rawClass = ReflectionHelper.getRawClass(contractOrImpl);

        if (root == null) {
            return Utilities.createService(activeDescriptor, originalRequest, this, null, rawClass);
        }

        ServiceHandleImpl<T> subHandle = internalGetServiceHandle(activeDescriptor, contractOrImpl);

        if (PerLookup.class.equals(activeDescriptor.getScopeAnnotation())) {
            ((ServiceHandleImpl<?>) root).addSubHandle((ServiceHandleImpl<T>) subHandle);
        }

        return subHandle.getService((ServiceHandle<T>) root);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getService(java.lang.reflect.Type)
     */
    @Override
    public <T> T getService(Class<T> contractOrImpl, Annotation... qualifiers) throws MultiException {
        return getService((Type) contractOrImpl, qualifiers);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getService(java.lang.reflect.Type)
     */
    @Override
    public <T> T getService(Type contractOrImpl, Annotation... qualifiers) throws MultiException {
        checkState();

        ActiveDescriptor<T> ad = internalGetDescriptor(null, contractOrImpl, null, null, qualifiers);
        if (ad == null){
            return null;
        }

        T retVal = Utilities.createService(ad, null, this, null, ReflectionHelper.getRawClass(contractOrImpl));

        return retVal;
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getService(java.lang.reflect.Type, java.lang.String)
     */
    @Override
    public <T> T getService(Class<T> contractOrImpl, String name, Annotation... qualifiers)
            throws MultiException {
        return internalGetService(contractOrImpl, name, qualifiers);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getService(java.lang.reflect.Type, java.lang.String)
     */
    @Override
    public <T> T getService(Type contractOrImpl, String name, Annotation... qualifiers)
            throws MultiException {
        return internalGetService(contractOrImpl, name, qualifiers);
    }

    private <T> T internalGetService(Type contractOrImpl, String name, Annotation... qualifiers) {
        checkState();

        ActiveDescriptor<T> ad = internalGetDescriptor(null, contractOrImpl, name, null, qualifiers);
        if (ad == null) return null;

        T retVal = Utilities.createService(ad, null, this, null, ReflectionHelper.getRawClass(contractOrImpl));

        return retVal;

    }

    /* package */ <T> T getUnqualifiedService(Type contractOrImpl, Unqualified unqualified, Annotation... qualifiers) throws MultiException {
        checkState();

        ActiveDescriptor<T> ad = internalGetDescriptor(null, contractOrImpl, null, unqualified, qualifiers);
        if (ad == null) return null;

        T retVal = Utilities.createService(ad, null, this, null, ReflectionHelper.getRawClass(contractOrImpl));

        return retVal;
    }

    private <T> List<T> protectedGetAllServices(final Type contractOrImpl,
            final Annotation... qualifiers) {
        return AccessController.doPrivileged(new PrivilegedAction<List<T>>() {

            @Override
            public List<T> run() {
                return getAllServices(contractOrImpl, qualifiers);
            }
        });
    }

    @Override
    public <T> List<T> getAllServices(Class<T> contractOrImpl, Annotation... qualifiers)
            throws MultiException {
        return getAllServices((Type) contractOrImpl, qualifiers);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getAllServices(java.lang.reflect.Type)
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> List<T> getAllServices(Type contractOrImpl, Annotation... qualifiers)
            throws MultiException {
        checkState();

        List<T> retVal = (List<T>) internalGetAllServiceHandles(
                contractOrImpl,
                null,
                false,
                qualifiers
                );

        return retVal;
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getAllServices(java.lang.annotation.Annotation, java.lang.annotation.Annotation[])
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> List<T> getAllServices(Annotation qualifier,
            Annotation... qualifiers) throws MultiException {
        checkState();

        List<ServiceHandle<?>> services = getAllServiceHandles(qualifier, qualifiers);

        List<T> retVal = new LinkedList<T>();
        for (ServiceHandle<?> service : services) {
            retVal.add((T) service.getService());
        }

        return retVal;
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getAllServices(org.glassfish.hk2.api.Filter)
     */
    @Override
    public List<?> getAllServices(Filter searchCriteria)
            throws MultiException {
        checkState();

        List<ServiceHandle<?>> handleSet = getAllServiceHandles(searchCriteria);

        List<Object> retVal = new LinkedList<Object>();
        for (ServiceHandle<?> handle : handleSet) {
            retVal.add(handle.getService());
        }

        return retVal;
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getName()
     */
    @Override
    public String getName() {
        return locatorName;
    }

    @Override
    public ServiceLocatorState getState() {
        rLock.lock();
        try {
            return state;
        } finally {
            rLock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#shutdown()
     */
    @Override
    public void shutdown() {
        
        
        wLock.lock();
        try {
            if (state.equals(ServiceLocatorState.SHUTDOWN)) return;

            if (parent != null) {
                parent.removeChild(this);
            }
        }
        finally {
            wLock.unlock();
        }
        
        // These things must be done OUTSIDE the lock
        List<ServiceHandle<?>> handles = getAllServiceHandles(BuilderHelper.createContractFilter(Context.class.getName()));

        for (ServiceHandle<?> handle : handles) {
            if (handle.isActive()) {
                Context<?> context = (Context<?>) handle.getService();
                context.shutdown();
            }
        }

        singletonContext.shutdown();
        wLock.lock();
        try {

            state = ServiceLocatorState.SHUTDOWN;

            allDescriptors.clear();
            descriptorsByAdvertisedContract.clear();
            descriptorsByName.clear();
            allResolvers.clear();
            injecteeToResolverCache.clear();
            allValidators.clear();
            errorHandlers.clear();
            igdCache.clear();
            igashCache.clear();
//            hybridIgdCacheEntries.clear();
//            hybridIgashCacheEntries.clear();
            synchronized (children) {
                children.clear();
            }

            Logger.getLogger().debug("Shutdown ServiceLocator " + this);
        } finally {
            wLock.unlock();
        }

        ServiceLocatorFactory.getInstance().destroy(this);

        Logger.getLogger().debug("ServiceLocator " + this + " has been shutdown");
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#create(java.lang.Class)
     */
    @Override
    public <T> T create(Class<T> createMe) {
        return create(createMe, null);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#create(java.lang.Class)
     */
    @Override
    public <T> T create(Class<T> createMe, String strategy) {
        checkState();

        return Utilities.justCreate(createMe, this, strategy);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#inject(java.lang.Object)
     */
    @Override
    public void inject(Object injectMe) {
        inject(injectMe, null);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#inject(java.lang.Object)
     */
    @Override
    public void inject(Object injectMe, String strategy) {
        checkState();

        Utilities.justInject(injectMe, this, strategy);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#postConstruct(java.lang.Object)
     */
    @Override
    public void postConstruct(Object postConstructMe) {
        postConstruct(postConstructMe, null);

    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#postConstruct(java.lang.Object)
     */
    @Override
    public void postConstruct(Object postConstructMe, String strategy) {
        checkState();

        if (postConstructMe == null) {
            throw new IllegalArgumentException();
        }

        if (((strategy == null) || strategy.equals(ClassAnalyzer.DEFAULT_IMPLEMENTATION_NAME)) &&
                (postConstructMe instanceof PostConstruct)) {
            ((PostConstruct) postConstructMe).postConstruct();
        }
        else {
            Utilities.justPostConstruct(postConstructMe, this, strategy);
        }

    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#preDestroy(java.lang.Object)
     */
    @Override
    public void preDestroy(Object preDestroyMe) {
        preDestroy(preDestroyMe, null);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#preDestroy(java.lang.Object)
     */
    @Override
    public void preDestroy(Object preDestroyMe, String strategy) {
        checkState();

        if (preDestroyMe == null) {
            throw new IllegalArgumentException();
        }

        if (((strategy == null) || strategy.equals(ClassAnalyzer.DEFAULT_IMPLEMENTATION_NAME)) &&
                (preDestroyMe instanceof PreDestroy)) {
            ((PreDestroy) preDestroyMe).preDestroy();
        }
        else {
            Utilities.justPreDestroy(preDestroyMe, this, strategy);
        }
    }

    /**
     * Creates, injects and postConstructs, all in one
     */
    @Override
    public <U> U createAndInitialize(Class<U> createMe) {
        return createAndInitialize(createMe, null);
    }

    /**
     * Creates, injects and postConstructs, all in one
     */
    @Override
    public <U> U createAndInitialize(Class<U> createMe, String strategy) {
        U retVal = create(createMe, strategy);
        inject(retVal, strategy);
        postConstruct(retVal, strategy);
        return retVal;
    }

    private static String getName(String name, Annotation... qualifiers) {
        if (name != null) return name;

        for (Annotation qualifier : qualifiers) {
            if (qualifier instanceof Named) {
                Named named = (Named) qualifier;
                if (named.value() != null && !named.value().isEmpty()) {
                    return named.value();
                }

            }
        }

        return null;
    }

    private final static class IgdCacheKey {
        private final CacheKey cacheKey;
        private final String name;
        private final Injectee onBehalfOf;
        private final Type contractOrImpl;
        private final Annotation[] qualifiers;
        private final Filter filter;

        private final int hashCode;

        IgdCacheKey(CacheKey key, String name, Injectee onBehalfOf, Type contractOrImpl, Class<?> rawClass, Annotation[] qualifiers, Filter filter) {
            this.cacheKey = key;
            this.name = name;
            this.onBehalfOf = onBehalfOf;
            this.contractOrImpl = contractOrImpl;
            this.qualifiers = qualifiers;
            this.filter = filter;

            int hash = 5;
            hash = 41 * hash + this.cacheKey.hashCode();

            this.hashCode = hash;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof IgdCacheKey)) {
                return false;
            }
            final IgdCacheKey other = (IgdCacheKey) obj;
            if (this.hashCode != other.hashCode) {
                return false;
            }
            if ((this.cacheKey == null) ? (other.cacheKey != null) : !this.cacheKey.equals(other.cacheKey)) {
                return false;
            }
            return true;
        }
    }

    private class IgdValue {
        final NarrowResults results;
        final ImmediateResults immediate;
        final AtomicInteger freshnessKeeper = new AtomicInteger(1);

        public IgdValue(NarrowResults results, ImmediateResults immediate) {
            this.results = results;
            this.immediate = immediate;
        }
    }

    final LRUHybridCache<IgdCacheKey, IgdValue> igdCache =
            new LRUHybridCache<IgdCacheKey, IgdValue>(CACHE_SIZE, new Computable<IgdCacheKey, HybridCacheEntry<IgdValue>>() {
        @Override
        public HybridCacheEntry<IgdValue> compute(final IgdCacheKey key) {

            final List<SystemDescriptor<?>> candidates = getDescriptors(key.filter, key.onBehalfOf, true, false, true);
            final ImmediateResults immediate = narrow(ServiceLocatorImpl.this,
                    candidates,
                    key.contractOrImpl,
                    key.name,
                    key.onBehalfOf,
                    true,
                    true,
                    null,
                    key.filter,
                    key.qualifiers);
            final NarrowResults results = immediate.getTimelessResults();
            if (!results.getErrors().isEmpty()) {
                Utilities.handleErrors(results, new LinkedList<ErrorService>(errorHandlers));
                return igdCache.createCacheEntry(key, new IgdValue(results, immediate), true);
            } else {
                return igdCache.createCacheEntry(key, new IgdValue(results, immediate), false);
            }
        }
    });


    @SuppressWarnings("unchecked")
    private <T> ActiveDescriptor<T> internalGetDescriptor(Injectee onBehalfOf, Type contractOrImpl,
            String name,
            Unqualified unqualified,
            Annotation... qualifiers) throws MultiException {
        if (contractOrImpl == null) throw new IllegalArgumentException();

        Class<?> rawClass = ReflectionHelper.getRawClass(contractOrImpl);
        if (rawClass == null) return null;  // Can't be a TypeVariable or Wildcard

        Utilities.checkLookupType(rawClass);

        rawClass = Utilities.translatePrimitiveType(rawClass);

        name = getName(name, qualifiers);

        final boolean useCache = unqualified == null;

        NarrowResults results = null;
        LinkedList<ErrorService> currentErrorHandlers = null;

        ImmediateResults immediate = null;

        if (!useCache) {
            final Filter filter = new UnqualifiedIndexedFilter(rawClass.getName(), name, unqualified);
            rLock.lock();
            try {
                List<SystemDescriptor<?>> candidates = getDescriptors(filter, onBehalfOf, true, false, true);
                immediate = narrow(this,
                        candidates,
                        contractOrImpl,
                        name,
                        onBehalfOf,
                        true,
                        true,
                        null,
                        filter,
                        qualifiers);
                results = immediate.getTimelessResults();
                if (!results.getErrors().isEmpty()) {
                    currentErrorHandlers = new LinkedList<ErrorService>(errorHandlers);
                    // was outside of the lock:
                    Utilities.handleErrors(results, currentErrorHandlers);
                }
            } finally {
                rLock.unlock();
            }

            // Must do validation here in order to allow for caching
            ActiveDescriptor<T> postValidateResult = immediate.getImmediateResults().isEmpty() ? null
                    : (ActiveDescriptor<T>) immediate.getImmediateResults().get(0);

            return postValidateResult;
        } else { // USE CACHE!

            final CacheKey cacheKey = new CacheKey(contractOrImpl, name, qualifiers);
            final Filter filter = BuilderHelper.createNameAndContractFilter(rawClass.getName(), name);
            final IgdCacheKey igdCacheKey = new IgdCacheKey(cacheKey, name, onBehalfOf, contractOrImpl, rawClass, qualifiers, filter);

            rLock.lock();
            try {
                final HybridCacheEntry<IgdValue> entry = igdCache.compute(igdCacheKey);
                final IgdValue value = entry.getValue();
                final boolean freshOne = value.freshnessKeeper.compareAndSet(1, 2);
                if (!freshOne) {
                    immediate = narrow(this,
                            null,
                            contractOrImpl,
                            name,
                            onBehalfOf,
                            true,
                            true,
                            value.results,
                            filter,
                            qualifiers);
                    results = immediate.getTimelessResults();
                } else {
                    results = value.results;
                    immediate = value.immediate;
                }

                if (!results.getErrors().isEmpty()) {
                    currentErrorHandlers = new LinkedList<ErrorService>(errorHandlers);
                }
            } finally {
                rLock.unlock();
            }

            if (currentErrorHandlers != null) {
                // Do this next call OUTSIDE of the lock
                Utilities.handleErrors(results, currentErrorHandlers);
            }

            // Must do validation here in order to allow for caching
            ActiveDescriptor<T> postValidateResult = immediate.getImmediateResults().isEmpty() ? null
                    : (ActiveDescriptor<T>) immediate.getImmediateResults().get(0);

            return postValidateResult;
        }
    }

    @Override
    public <T> ServiceHandle<T> getServiceHandle(Class<T> contractOrImpl,
            Annotation... qualifiers) throws MultiException {
        return getServiceHandle((Type) contractOrImpl, qualifiers);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getServiceHandle(java.lang.reflect.Type, java.lang.annotation.Annotation[])
     */
    @Override
    public <T> ServiceHandle<T> getServiceHandle(Type contractOrImpl,
            Annotation... qualifiers) throws MultiException {
        checkState();

        ActiveDescriptor<T> ad = internalGetDescriptor(null, contractOrImpl, null, null, qualifiers);
        if (ad == null) return null;

        return getServiceHandle(ad, new InjecteeImpl(contractOrImpl));
    }

    /* package */ <T> ServiceHandle<T> getUnqualifiedServiceHandle(Type contractOrImpl, Unqualified unqualified, Annotation... qualifiers) throws MultiException {
        checkState();

        ActiveDescriptor<T> ad = internalGetDescriptor(null, contractOrImpl, null, unqualified, qualifiers);
        if (ad == null) return null;

        return getServiceHandle(ad, new InjecteeImpl(contractOrImpl));
    }

    private List<ServiceHandle<?>> protectedGetAllServiceHandles(
            final Type contractOrImpl, final Annotation... qualifiers) {
        return AccessController.doPrivileged(new PrivilegedAction<List<ServiceHandle<?>>>() {

            @Override
            public List<ServiceHandle<?>> run() {
                return getAllServiceHandles(contractOrImpl, qualifiers);
            }

        });
    }

    @Override
    public <T> List<ServiceHandle<T>> getAllServiceHandles(
            Class<T> contractOrImpl, Annotation... qualifiers)
            throws MultiException {
        return Utilities.cast(getAllServiceHandles((Type) contractOrImpl, qualifiers));
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getAllServiceHandles(java.lang.reflect.Type, java.lang.annotation.Annotation[])
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<ServiceHandle<?>> getAllServiceHandles(
            Type contractOrImpl, Annotation... qualifiers)
            throws MultiException {
        return (List<ServiceHandle<?>>)
                internalGetAllServiceHandles(contractOrImpl, null, true, qualifiers);
    }

    /* package */ @SuppressWarnings("unchecked")
    List<ServiceHandle<?>> getAllUnqualifiedServiceHandles(
            Type contractOrImpl, Unqualified unqualified, Annotation... qualifiers)
            throws MultiException {
        return (List<ServiceHandle<?>>)
                internalGetAllServiceHandles(contractOrImpl, unqualified, true, qualifiers);
    }

    final private LRUHybridCache<IgdCacheKey, IgdValue> igashCache =
            new LRUHybridCache<IgdCacheKey, IgdValue>(CACHE_SIZE, new Computable<IgdCacheKey, HybridCacheEntry<IgdValue>>() {
        @Override
        public HybridCacheEntry<IgdValue> compute(final IgdCacheKey key) {

            List<SystemDescriptor<?>> candidates = getDescriptors(key.filter, null, true, false, true);
            ImmediateResults immediate = narrow(ServiceLocatorImpl.this,
                    candidates,
                    key.contractOrImpl,
                    null,
                    null,
                    false,
                    true,
                    null,
                    key.filter,
                    key.qualifiers);
            NarrowResults results = immediate.getTimelessResults();
            if (!results.getErrors().isEmpty()) {
                Utilities.handleErrors(results, new LinkedList<ErrorService>(errorHandlers));
                return igashCache.createCacheEntry(key, new IgdValue(results, immediate), true);
            } else {
                return igashCache.createCacheEntry(key, new IgdValue(results, immediate), false);
            }
        }
    });

    private List<?> internalGetAllServiceHandles(
            Type contractOrImpl,
            Unqualified unqualified,
            boolean getHandles,
            Annotation... qualifiers)
            throws MultiException {

        if (contractOrImpl == null) throw new IllegalArgumentException();
        checkState();

        final Class<?> rawClass = ReflectionHelper.getRawClass(contractOrImpl);
        if (rawClass == null) {
            throw new MultiException(new IllegalArgumentException("Type must be a class or parameterized type, it was " + contractOrImpl));
        }

        final boolean useCache = unqualified == null;
        final String name = rawClass.getName();

        NarrowResults results = null;
        LinkedList<ErrorService> currentErrorHandlers = null;

        ImmediateResults immediate = null;

        if (!useCache) {
            final Filter filter = new UnqualifiedIndexedFilter(name, null, unqualified);
            rLock.lock();
            try {
              List<SystemDescriptor<?>> candidates = getDescriptors(filter, null, true, false, true);
              immediate = narrow(this,
                      candidates,
                      contractOrImpl,
                      null,
                      null,
                      false,
                      true,
                      null,
                      filter,
                      qualifiers);
              results = immediate.getTimelessResults();
            if (!results.getErrors().isEmpty()) {
                currentErrorHandlers = new LinkedList<ErrorService>(errorHandlers);
            }
            } finally {
                rLock.unlock();
            }
        } else { // USE CACHE!

            final CacheKey cacheKey = new CacheKey(contractOrImpl, null, qualifiers);
            final Filter filter = BuilderHelper.createContractFilter(name);
            final IgdCacheKey igdCacheKey = new IgdCacheKey(cacheKey, name, null, contractOrImpl, rawClass, qualifiers, filter);

            rLock.lock();
            try {
                final HybridCacheEntry<IgdValue> entry = igashCache.compute(igdCacheKey);
                final IgdValue value = entry.getValue();
                final boolean freshOne = value.freshnessKeeper.compareAndSet(1, 2);
                if (!freshOne) {
                    immediate = narrow(this,
                            null,
                            contractOrImpl,
                            null,
                            null,
                            false,
                            true,
                            value.results,
                            filter,
                            qualifiers);
                    results = immediate.getTimelessResults();
                } else {
                    results = value.results;
                    immediate = value.immediate;
                }

                if (!results.getErrors().isEmpty()) {
                    currentErrorHandlers = new LinkedList<ErrorService>(errorHandlers);
                }
            } finally {
                rLock.unlock();
            }
        }

        if (currentErrorHandlers != null) {
            // Do this next call OUTSIDE of the lock
            Utilities.handleErrors(results, currentErrorHandlers);
        }

        LinkedList<Object> retVal = new LinkedList<Object>();
        for (ActiveDescriptor<?> candidate : immediate.getImmediateResults()) {
            if (getHandles) {
                retVal.add(internalGetServiceHandle(candidate, contractOrImpl));
            }
            else {
                Object service = Utilities.createService(candidate, null, this, null, rawClass);

                retVal.add(service);
            }
        }

        return retVal;
    }

    @Override
    public <T> ServiceHandle<T> getServiceHandle(Class<T> contractOrImpl,
            String name, Annotation... qualifiers) throws MultiException {
        return getServiceHandle((Type) contractOrImpl, name, qualifiers);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getServiceHandle(java.lang.reflect.Type, java.lang.String, java.lang.annotation.Annotation[])
     */
    @Override
    public <T> ServiceHandle<T> getServiceHandle(Type contractOrImpl,
            String name, Annotation... qualifiers) throws MultiException {
        checkState();

        ActiveDescriptor<T> ad = internalGetDescriptor(null, contractOrImpl, name, null, qualifiers);
        if (ad == null) return null;

        return internalGetServiceHandle(ad, contractOrImpl);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getAllServiceHandles(org.glassfish.hk2.api.Filter)
     */
    @Override
    public List<ServiceHandle<?>> getAllServiceHandles(
            Filter searchCriteria) throws MultiException {
        checkState();

        NarrowResults results;
        LinkedList<ErrorService> currentErrorHandlers = null;
        List<SystemDescriptor<?>> candidates = Utilities.cast(getDescriptors(searchCriteria));
        ImmediateResults immediate = narrow(this,
                candidates,
                null,
                null,
                null,
                false,
                false,
                null,
                searchCriteria);
        results = immediate.getTimelessResults();
        if (!results.getErrors().isEmpty()) {
            currentErrorHandlers = new LinkedList<ErrorService>(errorHandlers);
        }

        if (currentErrorHandlers != null) {
            // Do this next call OUTSIDE of the lock
            Utilities.handleErrors(results, currentErrorHandlers);
        }

        SortedSet<ServiceHandle<?>> retVal = new TreeSet<ServiceHandle<?>>(HANDLE_COMPARATOR);
        for (ActiveDescriptor<?> candidate : results.getResults()) {
            retVal.add(getServiceHandle(candidate));
        }

        return new LinkedList<ServiceHandle<?>>(retVal);
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getAllServiceHandles(java.lang.annotation.Annotation, java.lang.annotation.Annotation[])
     */
    @Override
    public List<ServiceHandle<?>> getAllServiceHandles(Annotation qualifier,
            Annotation... qualifiers) throws MultiException {
        checkState();

        if (qualifier == null) throw new IllegalArgumentException("qualifier is null");

        final Set<String> allQualifiers = new LinkedHashSet<String>();
        allQualifiers.add(qualifier.annotationType().getName());

        for (Annotation anno : qualifiers) {
            String addMe = anno.annotationType().getName();
            if (allQualifiers.contains(addMe)) {
                throw new IllegalArgumentException("Multiple qualifiers with name " + addMe);
            }

            allQualifiers.add(addMe);
        }

        return getAllServiceHandles(new Filter() {

            @Override
            public boolean matches(Descriptor d) {
                return d.getQualifiers().containsAll(allQualifiers);
            }

        });
    }
    
    /* package */ List<InterceptionService> getInterceptionServices() {
        if (!hasInterceptionServices) return null;
        
        rLock.lock();
        try {
            return new LinkedList<InterceptionService>(interceptionServices);
        }
        finally {
            rLock.unlock();
        }
    }

    /**
     * Checks the configuration operation before anything happens to the internal data structures.
     *
     * @param dci The configuration that contains the proposed modifications
     * @return A set of descriptors that is being removed fromthe configuration
     */
    private CheckConfigurationData checkConfiguration(DynamicConfigurationImpl dci) {
        List<SystemDescriptor<?>> retVal = new LinkedList<SystemDescriptor<?>>();
        boolean addOrRemoveOfInstanceListener = false;
        boolean addOrRemoveOfInjectionResolver = false;
        boolean addOrRemoveOfErrorHandler = false;
        boolean addOrRemoveOfClazzAnalyzer = false;
        boolean addOrRemoveOfConfigListener = false;
        boolean addOrRemoveOfInterceptionService = false;
        HashSet<String> affectedContracts = new HashSet<String>();

        for (Filter unbindFilter : dci.getUnbindFilters()) {
            List<SystemDescriptor<?>> results = getDescriptors(unbindFilter, null, false, false, true);

            for (SystemDescriptor<?> candidate : results) {
                affectedContracts.addAll(getAllContracts(candidate));

                if (retVal.contains(candidate)) continue;

                for (ValidationService vs : getAllValidators()) {
                    if (!vs.getValidator().validate(new ValidationInformationImpl(
                            Operation.UNBIND, candidate))) {
                        throw new MultiException(new IllegalArgumentException("Descriptor " +
                            candidate + " did not pass the UNBIND validation"));
                    }
                }

                if (candidate.getAdvertisedContracts().contains(InstanceLifecycleListener.class.getName())) {
                    addOrRemoveOfInstanceListener = true;
                }
                if (candidate.getAdvertisedContracts().contains(InjectionResolver.class.getName())) {
                    addOrRemoveOfInjectionResolver = true;
                }
                if (candidate.getAdvertisedContracts().contains(ErrorService.class.getName())) {
                    addOrRemoveOfErrorHandler = true;
                }
                if (candidate.getAdvertisedContracts().contains(ClassAnalyzer.class.getName())) {
                    addOrRemoveOfClazzAnalyzer = true;
                }
                if (candidate.getAdvertisedContracts().contains(DynamicConfigurationListener.class.getName())) {
                    addOrRemoveOfConfigListener = true;
                }
                if (candidate.getAdvertisedContracts().contains(InterceptionService.class.getName())) {
                    addOrRemoveOfInterceptionService = true;
                }

                retVal.add(candidate);
            }
        }

        for (SystemDescriptor<?> sd : dci.getAllDescriptors()) {
            affectedContracts.addAll(getAllContracts(sd));

            boolean checkScope = false;
            if (sd.getAdvertisedContracts().contains(ValidationService.class.getName()) ||
                sd.getAdvertisedContracts().contains(ErrorService.class.getName()) ||
                sd.getAdvertisedContracts().contains(InterceptionService.class.getName()) ||
                sd.getAdvertisedContracts().contains(InstanceLifecycleListener.class.getName())) {
                // These get reified right away
                reifyDescriptor(sd);

                checkScope = true;

                if (sd.getAdvertisedContracts().contains(ErrorService.class.getName())) {
                    addOrRemoveOfErrorHandler = true;
                }
                if (sd.getAdvertisedContracts().contains(InstanceLifecycleListener.class.getName())) {
                    addOrRemoveOfInstanceListener = true;
                }
                if (sd.getAdvertisedContracts().contains(InterceptionService.class.getName())) {
                    addOrRemoveOfInterceptionService = true;
                }
            }

            if (sd.getAdvertisedContracts().contains(InjectionResolver.class.getName())) {
                // This gets reified right away
                reifyDescriptor(sd);

                checkScope = true;

                if (Utilities.getInjectionResolverType(sd) == null) {
                    throw new MultiException(new IllegalArgumentException(
                            "An implementation of InjectionResolver must be a parameterized type and the actual type" +
                            " must be an annotation"));
                }

                addOrRemoveOfInjectionResolver = true;
            }
            
            if (sd.getAdvertisedContracts().contains(DynamicConfigurationListener.class.getName())) {
                // This gets reified right away
                reifyDescriptor(sd);
                
                checkScope = true;
                
                addOrRemoveOfConfigListener = true;
            }

            if (sd.getAdvertisedContracts().contains(Context.class.getName())) {
                // This one need not be reified, it will get checked later
                checkScope = true;
            }

            if (sd.getAdvertisedContracts().contains(ClassAnalyzer.class.getName())) {
                addOrRemoveOfClazzAnalyzer = true;
            }

            if (checkScope) {
                String scope = (sd.getScope() == null) ? PerLookup.class.getName() : sd.getScope() ;

                if (!scope.equals(Singleton.class.getName())) {
                    throw new MultiException(new IllegalArgumentException(
                            "The implementation class " +  sd.getImplementation() + " must be in the Singleton scope"));
                }
            }

            for (ValidationService vs : getAllValidators()) {
                Validator validator = vs.getValidator();
                if (validator == null) {
                    throw new MultiException(new IllegalArgumentException("Validator was null from validation service" + vs));
                }

                if (!vs.getValidator().validate(new ValidationInformationImpl(
                        Operation.BIND, sd))) {
                    throw new MultiException(new IllegalArgumentException("Descriptor " + sd + " did not pass the BIND validation"));
                }
            }
        }

        return new CheckConfigurationData(retVal,
                addOrRemoveOfInstanceListener,
                addOrRemoveOfInjectionResolver,
                addOrRemoveOfErrorHandler,
                addOrRemoveOfClazzAnalyzer,
                addOrRemoveOfConfigListener,
                affectedContracts,
                addOrRemoveOfInterceptionService);
    }

    private static List<String> getAllContracts(ActiveDescriptor<?> desc) {
        LinkedList<String> allContracts = new LinkedList<String>(desc.getAdvertisedContracts());
        allContracts.addAll(desc.getQualifiers());
        String scope = (desc.getScope() == null) ? PerLookup.class.getName() : desc.getScope() ;
        allContracts.add(scope);

        return allContracts;
    }

    @SuppressWarnings("unchecked")
    private void removeConfigurationInternal(List<SystemDescriptor<?>> unbinds) {
        for (SystemDescriptor<?> unbind : unbinds) {
            if ((BIND_TRACING_PATTERN != null) && doTrace(unbind)) {
                Logger.getLogger().debug("HK2 Bind Tracing: Removing Descriptor " + unbind);
                if (BIND_TRACING_STACKS) {
                    Logger.getLogger().debug("ServiceLocatorImpl", "removeConfigurationInternal", new Throwable());
                }
            }

            allDescriptors.removeDescriptor(unbind);

            for (String advertisedContract : getAllContracts(unbind)) {
                IndexedListData ild = descriptorsByAdvertisedContract.get(advertisedContract);
                if (ild == null) continue;

                ild.removeDescriptor(unbind);
                if (ild.isEmpty()) descriptorsByAdvertisedContract.remove(advertisedContract);
            }

            String unbindName = unbind.getName();
            if (unbindName != null) {
                IndexedListData ild = descriptorsByName.get(unbindName);
                if (ild != null) {
                    ild.removeDescriptor(unbind);
                    if (ild.isEmpty()) {
                        descriptorsByName.remove(unbindName);
                    }
                }
            }

            if (unbind.getAdvertisedContracts().contains(ValidationService.class.getName())) {
                ServiceHandle<ValidationService> handle = (ServiceHandle<ValidationService>) getServiceHandle(unbind);
                ValidationService vs = handle.getService();
                allValidators.remove(vs);
            }
            
            if (unbind.isReified()) {
                for (Injectee injectee : unbind.getInjectees()) {
                    injecteeToResolverCache.remove(injectee);
                }
            }
        }
    }

    private static boolean doTrace(ActiveDescriptor<?> desc) {
        if (BIND_TRACING_PATTERN == null) return false;
        if ("*".equals(BIND_TRACING_PATTERN)) return true;

        if (desc.getImplementation() == null) return true;  // Null here matches everything

        StringTokenizer st = new StringTokenizer(BIND_TRACING_PATTERN, "|");
        while (st.hasMoreTokens()) {
            String token = st.nextToken();

            if (desc.getImplementation().contains(token)) {
                return true;
            }

            for (String contract : desc.getAdvertisedContracts()) {
                if (contract.contains(token)) return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private List<SystemDescriptor<?>> addConfigurationInternal(DynamicConfigurationImpl dci) {
        List<SystemDescriptor<?>> thingsAdded = new LinkedList<SystemDescriptor<?>>();

        for (SystemDescriptor<?> sd : dci.getAllDescriptors()) {
            if ((BIND_TRACING_PATTERN != null) && doTrace(sd)) {
                Logger.getLogger().debug("HK2 Bind Tracing: Adding Descriptor " + sd);
                if (BIND_TRACING_STACKS) {
                    Logger.getLogger().debug("ServiceLocatorImpl", "addConfigurationInternal", new Throwable());
                }
            }

            thingsAdded.add(sd);
            allDescriptors.addDescriptor(sd);

            List<String> allContracts = getAllContracts(sd);

            for (String advertisedContract : allContracts) {
                IndexedListData ild = descriptorsByAdvertisedContract.get(advertisedContract);
                if (ild == null) {
                    ild = new IndexedListData();
                    descriptorsByAdvertisedContract.put(advertisedContract, ild);
                }

                ild.addDescriptor(sd);
            }

            if (sd.getName() != null) {
                String name = sd.getName();
                IndexedListData ild = descriptorsByName.get(name);
                if (ild == null) {
                    ild = new IndexedListData();
                    descriptorsByName.put(name, ild);
                }

                ild.addDescriptor(sd);
            }

            if (sd.getAdvertisedContracts().contains(ValidationService.class.getName())) {
                ServiceHandle<ValidationService> handle = getServiceHandle((ActiveDescriptor<ValidationService>) sd);
                ValidationService vs = handle.getService();
                allValidators.add(vs);
            }
        }

        return thingsAdded;
    }

    private void reupInjectionResolvers() {
        HashMap<Class<? extends Annotation>, InjectionResolver<?>> newResolvers =
                new HashMap<Class<? extends Annotation>, InjectionResolver<?>>();

        Filter injectionResolverFilter = BuilderHelper.createContractFilter(
                InjectionResolver.class.getName());

        List<ActiveDescriptor<?>> resolverDescriptors = protectedGetDescriptors(injectionResolverFilter);

        for (ActiveDescriptor<?> resolverDescriptor : resolverDescriptors) {
            Class<? extends Annotation> iResolve = Utilities.getInjectionResolverType(resolverDescriptor);

            if (iResolve != null && !newResolvers.containsKey(iResolve)) {
                InjectionResolver<?> resolver = (InjectionResolver<?>)
                        getServiceHandle(resolverDescriptor).getService();

                newResolvers.put(iResolve, resolver);
            }
        }

        synchronized (allResolvers) {
            allResolvers.clear();
            allResolvers.putAll(newResolvers);
        }
        injecteeToResolverCache.clear();
    }
    
    private void reupInterceptionServices() {
        List<InterceptionService> allInterceptionServices = protectedGetAllServices(InterceptionService.class);

        interceptionServices.clear();
        interceptionServices.addAll(allInterceptionServices);
        
        hasInterceptionServices = !interceptionServices.isEmpty();
    }

    private void reupErrorHandlers() {
        List<ErrorService> allErrorServices = protectedGetAllServices(ErrorService.class);

        errorHandlers.clear();
        errorHandlers.addAll(allErrorServices);
    }
    
    private void reupConfigListeners() {
        List<ServiceHandle<?>> allConfigListeners = protectedGetAllServiceHandles(DynamicConfigurationListener.class);

        configListeners.clear();
        configListeners.addAll(allConfigListeners);
    }

    private void reupInstanceListenersHandlers(Collection<SystemDescriptor<?>> checkList) {
        List<InstanceLifecycleListener> allLifecycleListeners = protectedGetAllServices(InstanceLifecycleListener.class);

        for (SystemDescriptor<?> descriptor : checkList) {
            descriptor.reupInstanceListeners(allLifecycleListeners);
        }
    }

    @SuppressWarnings("unchecked")
    private void reupClassAnalyzers() {
        List<ServiceHandle<?>> allAnalyzers = protectedGetAllServiceHandles(ClassAnalyzer.class);

        synchronized (classAnalyzerLock) {
            classAnalyzers.clear();

            for (ServiceHandle<?> handle : allAnalyzers) {
                ActiveDescriptor<?> descriptor = handle.getActiveDescriptor();
                String name = descriptor.getName();
                if (name == null) continue;

                ClassAnalyzer created = ((ServiceHandle<ClassAnalyzer>) handle).getService();
                if (created == null) continue;

                classAnalyzers.put(name, created);
            }
        }
    }

    private void reupCache(HashSet<String> affectedContracts) {
        // This lock must be acquired as reupCache is called on children
        wLock.lock();
        try {
            for (String affectedContract : affectedContracts) {
                final String fAffectedContract = affectedContract;
                final CacheKeyFilter<IgdCacheKey> cacheKeyFilter = new CacheKeyFilter<IgdCacheKey>() {
                    @Override
                    public boolean matches(IgdCacheKey key) {
                        return key.cacheKey.matchesRemovalName(fAffectedContract);
                    }
                };

                igdCache.releaseMatching(cacheKeyFilter);
                igashCache.releaseMatching(cacheKeyFilter);
            }
        } finally {
            wLock.unlock();
        }
    }

    private void reup(List<SystemDescriptor<?>> thingsAdded,
            boolean instanceListenersModified,
            boolean injectionResolversModified,
            boolean errorHandlersModified,
            boolean classAnalyzersModified,
            boolean dynamicConfigurationListenersModified,
            HashSet<String> affectedContracts,
            boolean interceptionServicesModified) {

        // This MUST come before the other re-ups, in case the other re-ups look for
        // items that may have previously been cached
        reupCache(affectedContracts);

        if (injectionResolversModified) {
            reupInjectionResolvers();
        }

        if (errorHandlersModified) {
            reupErrorHandlers();
        }
        
        if (dynamicConfigurationListenersModified) {
            reupConfigListeners();
        }

        if (instanceListenersModified) {
            reupInstanceListenersHandlers(allDescriptors.getSortedList());
        }
        else {
            reupInstanceListenersHandlers(thingsAdded);
        }

        if (classAnalyzersModified) {
            reupClassAnalyzers();
        }
        
        // Should be last in order to ensure none of the
        // things added in this update are intercepted
        if (interceptionServicesModified) {
            reupInterceptionServices();
        }

        contextCache.clear();
    }

    private void getAllChildren(LinkedList<ServiceLocatorImpl> allMyChildren) {
        LinkedList<ServiceLocatorImpl> addMe;
        synchronized (children) {
            addMe = new LinkedList<ServiceLocatorImpl>(children.keySet());
        }

        allMyChildren.addAll(addMe);

        for (ServiceLocatorImpl sli : addMe) {
            sli.getAllChildren(allMyChildren);
        }
    }
    
    private void callAllConfigurationListeners(List<ServiceHandle<?>> allListeners) {
        if (allListeners == null) return;
        
        for (ServiceHandle<?> listener : allListeners) {
            ActiveDescriptor<?> listenerDescriptor = listener.getActiveDescriptor();
            if (listenerDescriptor.getLocatorId() != id) continue;
            
            try {
                ((DynamicConfigurationListener) listener.getService()).configurationChanged();
            }
            catch (Throwable th) {
                // Intentionally ignore
            }
        }
    }

    /* package */ void addConfiguration(DynamicConfigurationImpl dci) {
        CheckConfigurationData checkData;
        
        List<ServiceHandle<?>> allConfigurationListeners = null;
        MultiException configurationError = null;

        wLock.lock();
        try {
            checkData = checkConfiguration(dci);  // Does as much preliminary checking as possible

            removeConfigurationInternal(checkData.getUnbinds());

            List<SystemDescriptor<?>> thingsAdded = addConfigurationInternal(dci);

            reup(thingsAdded,
                    checkData.getInstanceLifecycleModificationsMade(),
                    checkData.getInjectionResolverModificationMade(),
                    checkData.getErrorHandlerModificationMade(),
                    checkData.getClassAnalyzerModificationMade(),
                    checkData.getDynamicConfigurationListenerModificationMade(),
                    checkData.getAffectedContracts(),
                    checkData.getInterceptionServiceModificationMade());
            
            allConfigurationListeners = new LinkedList<ServiceHandle<?>>(configListeners);
        } catch (MultiException me) {
            configurationError = me;
            throw me;
        } finally {
            List<ErrorService> errorServices = null;
            if (configurationError != null) {
                errorServices = new LinkedList<ErrorService>(errorHandlers);
            }
            
            wLock.unlock();
            
            if (errorServices != null && !errorServices.isEmpty()) {
                for (ErrorService errorService : errorServices) {
                    try {
                        errorService.onFailure(new ErrorInformationImpl(
                            ErrorType.DYNAMIC_CONFIGURATION_FAILURE,
                            null,
                            null,
                            configurationError));
                    }
                    catch (Throwable th) {
                        // Ignore
                    }
                }
                
            }
        }

        LinkedList<ServiceLocatorImpl> allMyChildren = new LinkedList<ServiceLocatorImpl>();
        getAllChildren(allMyChildren);

        for (ServiceLocatorImpl sli : allMyChildren) {
            sli.reupCache(checkData.getAffectedContracts());
        }
        
        callAllConfigurationListeners(allConfigurationListeners);
    }

    /* package */ boolean isInjectAnnotation(Annotation annotation) {
        return allResolvers.containsKey(annotation.annotationType());
    }

    /* package */ boolean isInjectAnnotation(Annotation annotation, boolean isConstructor) {
        InjectionResolver<?> resolver;
        resolver = allResolvers.get(annotation.annotationType());

        if (resolver == null) return false;

        if (isConstructor) {
            return resolver.isConstructorParameterIndicator();
        }

        return resolver.isMethodParameterIndicator();
    }

    /* package */ InjectionResolver<?> getInjectionResolver(Class<? extends Annotation> annoType) {
        return allResolvers.get(annoType);
    }

    private Context<?> _resolveContext(final Class<? extends Annotation> scope) throws IllegalStateException {
        Context<?> retVal = null;
        Type actuals[] = new Type[1];
        actuals[0] = scope;
        ParameterizedType findContext = new ParameterizedTypeImpl(Context.class, actuals);
        List<ServiceHandle<Context<?>>> contextHandles = Utilities.<List<ServiceHandle<Context<?>>>>cast(
            protectedGetAllServiceHandles(findContext));
        for (ServiceHandle<Context<?>> contextHandle : contextHandles) {
            Context<?> context = contextHandle.getService();

            if (!context.isActive()) continue;

            if (retVal != null) {
                throw new IllegalStateException("There is more than one active context for " + scope.getName());
            }

            retVal = context;
        }
        if (retVal == null) {
            throw new IllegalStateException("Could not find an active context for " + scope.getName());
        }
        return retVal;
    }

    /* package */ Context<?> resolveContext(Class<? extends Annotation> scope) throws IllegalStateException {
        if (scope.equals(Singleton.class)) return singletonContext;
        if (scope.equals(PerLookup.class)) return perLookupContext;
        return contextCache.compute(scope);
    }

    private Class<?> loadClass(Descriptor descriptor, Injectee injectee) {
        if (descriptor == null) throw new IllegalArgumentException();

        HK2Loader loader = descriptor.getLoader();
        if (loader == null) {
            return Utilities.loadClass(descriptor.getImplementation(), injectee);
        }

        Class<?> retVal;
        try {
            retVal = loader.loadClass(descriptor.getImplementation());
        }
        catch (MultiException me) {
            me.addError(new IllegalStateException("Could not load descriptor " + descriptor));

            throw me;
        }
        catch (Throwable th) {
            MultiException me = new MultiException(th);
            me.addError(new IllegalStateException("Could not load descriptor " + descriptor));

            throw me;
        }

        return retVal;
    }

    private ImmediateResults narrow(ServiceLocator locator,
            List<SystemDescriptor<?>> candidates,
            Type requiredType,
            String name,
            Injectee injectee,
            boolean onlyOne,
            boolean doValidation,
            NarrowResults cachedResults,
            Filter filter,
            Annotation... qualifiers) {
        ImmediateResults retVal = new ImmediateResults(cachedResults);
        cachedResults = retVal.getTimelessResults();

        if (candidates != null) {
            List<ActiveDescriptor<?>> lCandidates = Utilities.cast(candidates);
            cachedResults.setUnnarrowedResults(lCandidates);
        }

        Set<Annotation> requiredAnnotations = Utilities.fixAndCheckQualifiers(qualifiers, name);

        for (ActiveDescriptor<?> previousResult : cachedResults.getResults()) {
            if (doValidation && !validate((SystemDescriptor<?>) previousResult, injectee, filter)) continue;

            retVal.addValidatedResult(previousResult);

            if (onlyOne) return retVal;
        }

        if ((requiredType != null) &&
                (requiredType instanceof Class) &&
                ((Class<?>) requiredType).isAnnotation()) {
            // In the annotation case we need not do type checking, so do not reify
            requiredType = null;
        }

        ActiveDescriptor<?> candidate;
        while ((candidate = cachedResults.removeUnnarrowedResult()) != null) {
            boolean doReify = false;
            if ((requiredType != null || !requiredAnnotations.isEmpty()) &&
                    !candidate.isReified()) {
                doReify = true;
            }

            if (doReify) {
                try {
                    candidate = locator.reifyDescriptor(candidate, injectee);
                }
                catch (MultiException me) {
                    cachedResults.addError(candidate, injectee, me);
                    continue;
                }
                catch (Throwable th) {
                    cachedResults.addError(candidate, injectee, new MultiException(th));
                    continue;
                }
            }

            // Now match it
            if (requiredType != null) {
                boolean safe = false;
                for (Type candidateType : candidate.getContractTypes()) {
                    if (Utilities.isTypeSafe(requiredType, candidateType)) {
                        safe = true;
                        break;
                    }
                }

                if (!safe) {
                    // Sorry, not type safe
                    continue;
                }
            }

            // Now match the qualifiers

            // Checking requiredAnnotations isEmpty is a performance optimization which avoids
            // a potentially expensive doPriv call in the second part of the AND statement
            if (!requiredAnnotations.isEmpty()) {
                Set<Annotation> candidateAnnotations = candidate.getQualifierAnnotations();

                if (!ReflectionHelper.annotationContainsAll(candidateAnnotations, requiredAnnotations)) {
                    // The qualifiers do not match
                    continue;
                }
            }

            // If we are here, then this one matches
            cachedResults.addGoodResult(candidate);

            if (doValidation && !validate((SystemDescriptor<?>) candidate, injectee, filter)) continue;
            retVal.addValidatedResult(candidate);

            if (onlyOne) return retVal;
        }

        return retVal;
    }

    /* (non-Javadoc)
     * @see org.glassfish.hk2.api.ServiceLocator#getLocatorId()
     */
    @Override
    public long getLocatorId() {
        return id;
    }

    /* package */ long getNextServiceId() {
//        wLock.lock();
//        try {
            return nextServiceId.getAndIncrement();
//        } finally {
//            wLock.unlock();
//        }
    }

    private void addChild(ServiceLocatorImpl child) {
        synchronized (children) {
            children.put(child, null);
        }
    }

    private void removeChild(ServiceLocatorImpl child) {
        synchronized (children) {
            children.remove(child);
        }
    }

    private void checkState() {
        if (ServiceLocatorState.SHUTDOWN.equals(state)) throw new IllegalStateException(this + " has been shut down");
    }

    private LinkedHashSet<ValidationService> getAllValidators() {
        if (parent == null) {
            return allValidators;
        }

        LinkedHashSet<ValidationService> retVal = new LinkedHashSet<ValidationService>();

        retVal.addAll(parent.getAllValidators());
        retVal.addAll(allValidators);

        return retVal;
    }

    @Override
    public String getDefaultClassAnalyzerName() {
        synchronized (classAnalyzerLock) {
            return defaultClassAnalyzer;
        }
    }

    @Override
    public void setDefaultClassAnalyzerName(String defaultClassAnalyzer) {
        synchronized (classAnalyzerLock) {
            if (defaultClassAnalyzer == null) {
                this.defaultClassAnalyzer = ClassAnalyzer.DEFAULT_IMPLEMENTATION_NAME;
            }
            else {
                this.defaultClassAnalyzer = defaultClassAnalyzer;
            }
        }
    }

    /* package */ ClassAnalyzer getAnalyzer(String name, Collector collector) {
        ClassAnalyzer retVal;
        synchronized (classAnalyzerLock) {
            if (name == null) {
                name = defaultClassAnalyzer ;
            }

            retVal = classAnalyzers.get(name);
        }

        if (retVal == null) {
            collector.addThrowable(new IllegalStateException(
                    "Could not find an implementation of ClassAnalyzer with name " +
                    name));
            return null;
        }

        return retVal;
    }

    private static class CheckConfigurationData {
        private final List<SystemDescriptor<?>> unbinds;
        private final boolean instanceLifeycleModificationMade;
        private final boolean injectionResolverModificationMade;
        private final boolean errorHandlerModificationMade;
        private final boolean classAnalyzerModificationMade;
        private final boolean dynamicConfigurationListenerModificationMade;
        private final HashSet<String> affectedContracts;
        private final boolean interceptionServiceModificationMade;

        private CheckConfigurationData(List<SystemDescriptor<?>> unbinds,
                boolean instanceLifecycleModificationMade,
                boolean injectionResolverModificationMade,
                boolean errorHandlerModificationMade,
                boolean classAnalyzerModificationMade,
                boolean dynamicConfigurationListenerModificationMade,
                HashSet<String> affectedContracts,
                boolean interceptionServiceModificationMade) {
            this.unbinds = unbinds;
            this.instanceLifeycleModificationMade = instanceLifecycleModificationMade;
            this.injectionResolverModificationMade = injectionResolverModificationMade;
            this.errorHandlerModificationMade = errorHandlerModificationMade;
            this.classAnalyzerModificationMade = classAnalyzerModificationMade;
            this.dynamicConfigurationListenerModificationMade = dynamicConfigurationListenerModificationMade;
            this.affectedContracts = affectedContracts;
            this.interceptionServiceModificationMade = interceptionServiceModificationMade;
        }

        private List<SystemDescriptor<?>> getUnbinds() {
            return unbinds;
        }

        private boolean getInstanceLifecycleModificationsMade() {
            return instanceLifeycleModificationMade;
        }

        private boolean getInjectionResolverModificationMade() {
            return injectionResolverModificationMade;
        }

        private boolean getErrorHandlerModificationMade() {
            return errorHandlerModificationMade;
        }

        private boolean getClassAnalyzerModificationMade() {
            return classAnalyzerModificationMade;
        }
        
        private boolean getDynamicConfigurationListenerModificationMade() {
            return dynamicConfigurationListenerModificationMade;
        }

        private HashSet<String> getAffectedContracts() {
            return affectedContracts;
        }
        
        private boolean getInterceptionServiceModificationMade() {
            return interceptionServiceModificationMade;
        }
    }

    private static class UnqualifiedIndexedFilter implements IndexedFilter {
        private final String contract;
        private final String name;
        private final Unqualified unqualified;

        private UnqualifiedIndexedFilter(String contract, String name, Unqualified unqualified) {
            this.contract = contract;
            this.name = name;
            this.unqualified = unqualified;
        }

        @Override
        public boolean matches(Descriptor d) {
            Class<? extends Annotation> unqualifiedAnnos[] = unqualified.value();

            if (unqualifiedAnnos.length <= 0) {
                return (d.getQualifiers().isEmpty());
            }

            Set<String> notAllowed = new HashSet<String>();
            for (Class<? extends Annotation> notMe : unqualifiedAnnos) {
                notAllowed.add(notMe.getName());
            }

            for (String qualifier : d.getQualifiers()) {
                if (notAllowed.contains(qualifier)) return false;
            }

            return true;
        }

        @Override
        public String getAdvertisedContract() {
            return contract;
        }

        @Override
        public String getName() {
            return name;
        }

    }

    @Override
    public ServiceLocator getParent() {
        return parent;
    }

    @Override
    public boolean getNeutralContextClassLoader() {
        return neutralContextClassLoader;
    }

    @Override
    public void setNeutralContextClassLoader(boolean neutralContextClassLoader) {
        wLock.lock();
        try {
            this.neutralContextClassLoader = neutralContextClassLoader;
        }
        finally {
            wLock.unlock();
        }

    }
    
    /**
     * Used to get the ServiceLocatorImpl in inner classes
     * 
     * @return This current object
     */
    private ServiceLocatorImpl getMe() {
        return this;
    }
    
    /* package */ InjectionResolver<?> getInjectionResolverForInjectee(Injectee injectee) {
        return injecteeToResolverCache.compute(injectee);
        
    }

    public String toString() {
        return "ServiceLocatorImpl(" + locatorName + "," + id + "," + System.identityHashCode(this) + ")";
    }


}
