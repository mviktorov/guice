package com.vaadin.guice.server;

import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

import com.vaadin.guice.annotation.ForUI;
import com.vaadin.guice.annotation.GuiceUI;
import com.vaadin.guice.annotation.Import;
import com.vaadin.guice.annotation.OverrideBindings;
import com.vaadin.guice.annotation.PackagesToScan;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.server.BootstrapListener;
import com.vaadin.server.DeploymentConfiguration;
import com.vaadin.server.ServiceException;
import com.vaadin.server.SessionInitEvent;
import com.vaadin.server.SessionInitListener;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServiceInitListener;
import com.vaadin.server.VaadinServlet;
import com.vaadin.server.VaadinServletService;
import com.vaadin.server.VaadinSession;
import com.vaadin.ui.Component;
import com.vaadin.ui.UI;

import org.reflections.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Iterables.concat;
import static com.google.inject.Guice.createInjector;
import static com.google.inject.util.Modules.override;
import static java.lang.reflect.Modifier.isAbstract;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Subclass of the standard {@link com.vaadin.server.VaadinServlet Vaadin servlet} that adds a
 * {@link GuiceUIProvider} to every new Vaadin session
 *
 * @author Bernd Hopp (bernd@vaadin.com)
 */
@SuppressWarnings("unused")
public class GuiceVaadinServlet extends VaadinServlet implements SessionInitListener {

    private final Class<? super Provider<Injector>> injectorProviderType = new TypeLiteral<Provider<Injector>>() {
    }.getRawType();
    private GuiceViewProvider viewProvider;
    private GuiceUIProvider guiceUIProvider;
    private UIScope uiScoper;
    private Set<Class<? extends UI>> uis;
    private Set<Class<? extends View>> views;
    private Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> viewChangeListeners;
    private Injector injector;
    private VaadinSessionScope vaadinSessionScoper;
    private Set<Class<? extends VaadinServiceInitListener>> vaadinServiceInitListeners;
    private Set<Class<? extends BootstrapListener>> bootStrapListenerClasses;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        final String initParameter = servletConfig.getInitParameter("packagesToScan");

        final String[] packagesToScan;

        if (!isNullOrEmpty(initParameter)) {
            packagesToScan = initParameter.split(",");
        } else if (getClass().isAnnotationPresent(PackagesToScan.class)) {
            packagesToScan = getClass().getAnnotation(PackagesToScan.class).value();
        } else {
            throw new IllegalStateException("no packagesToScan-initParameter found and no @PackagesToScan-annotation present, please configure the packages to be scanned");
        }

        Reflections reflections = new Reflections((Object[]) packagesToScan);

        final Set<Module> modulesFromAnnotations = stream(getClass().getAnnotations())
                .filter(annotation -> annotation.getClass().isAnnotationPresent(Import.class))
                .map(annotation -> createModule(annotation.getClass().getAnnotation(Import.class).value(), reflections, annotation))
                .collect(toSet());

        Set<Class<? extends Module>> modulesFromAnnotationClasses = modulesFromAnnotations
                .stream()
                .map(Module::getClass)
                .collect(toSet());

        final Set<Module> modulesFromPath = nonAbstractSubtypes(reflections, Module.class)
                .stream()
                .filter(moduleClass -> !modulesFromAnnotationClasses.contains(moduleClass))
                .map(moduleClass -> createModule(moduleClass, reflections, null))
                .collect(toSet());

        Iterable<Module> allModules = concat(
                modulesFromAnnotations,
                modulesFromPath
        );

        List<Module> nonOverrideModules = new ArrayList<>();
        List<Module> overrideModules = new ArrayList<>();

        for (Module module : allModules) {
            if (module.getClass().isAnnotationPresent(OverrideBindings.class)) {
                overrideModules.add(module);
            } else {
                nonOverrideModules.add(module);
            }
        }

        /*
         * combine bindings from the static modules in {@link GuiceVaadinServletConfiguration#modules()} with those bindings
         * from dynamically loaded modules, see {@link RuntimeModule}.
         * This is done first so modules can install their own reflections.
        */
        Module combinedModules = override(nonOverrideModules).with(overrideModules);

        this.views = nonAbstractSubtypes(reflections, View.class);
        this.uis = nonAbstractSubtypes(reflections, UI.class);
        this.bootStrapListenerClasses = nonAbstractSubtypes(reflections, BootstrapListener.class);
        this.vaadinServiceInitListeners = nonAbstractSubtypes(reflections, VaadinServiceInitListener.class);
        this.uiScoper = new UIScope();
        this.vaadinSessionScoper = new VaadinSessionScope();
        this.viewProvider = new GuiceViewProvider(views, this);
        this.guiceUIProvider = new GuiceUIProvider(this);

        this.viewChangeListeners = uis
                .stream()
                .collect(toMap(uiClass -> uiClass, uiClass -> new HashSet<>()));

        uis.forEach(ui -> viewChangeListeners.put(ui, new HashSet<>()));

        for (Class<? extends ViewChangeListener> viewChangeListenerClass : nonAbstractSubtypes(reflections, ViewChangeListener.class)) {

            final ForUI annotation = viewChangeListenerClass.getAnnotation(ForUI.class);

            if (annotation == null) {
                viewChangeListeners.values().forEach(listeners -> listeners.add(viewChangeListenerClass));
            } else {
                checkArgument(annotation.value().length > 0, "ForUI#value must contain one ore more UI-classes");

                for (Class<? extends UI> applicableUiClass : annotation.value()) {
                    final Set<Class<? extends ViewChangeListener>> viewChangeListenersForUI = viewChangeListeners.get(applicableUiClass);

                    checkArgument(
                            viewChangeListenersForUI != null,
                            "%s is listed as applicableUi in the @ForUI-annotation of %s, but is not annotated with @GuiceUI"
                    );

                    final Class<? extends Component> viewContainer = applicableUiClass.getAnnotation(GuiceUI.class).viewContainer();

                    checkArgument(!viewContainer.equals(Component.class), "%s is annotated as @ForUI for %s, however viewContainer() is not set in @GuiceUI");

                    viewChangeListenersForUI.add(viewChangeListenerClass);
                }
            }
        }


        //sets up the basic vaadin stuff like UISetup
        VaadinModule vaadinModule = new VaadinModule(this);

        this.injector = createInjector(vaadinModule, combinedModules);

        super.init(servletConfig);
    }

    private <U> Set<Class<? extends U>> nonAbstractSubtypes(Reflections reflections, Class<U> type) {
        return reflections
                .getSubTypesOf(type)
                .stream()
                .filter(subtype -> !isAbstract(subtype.getModifiers()))
                .collect(toSet());
    }

    @Override
    protected void servletInitialized() throws ServletException {
        VaadinService
                .getCurrent()
                .addSessionInitListener(this);
    }

    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration) throws ServiceException {
        return new GuiceVaadinServletService(this, deploymentConfiguration);
    }

    @Override
    public void sessionInit(SessionInitEvent event) throws ServiceException {
        VaadinSession session = event.getSession();

        // remove UIProvider instances to avoid mapping
        // extraneous UIs if e.g. a servlet is declared as a nested
        // class in a UI class
        session.getUIProviders().forEach(session::removeUIProvider);

        //set the GuiceUIProvider
        session.addUIProvider(guiceUIProvider);

        bootStrapListenerClasses
                .stream()
                .map(getInjector()::getInstance)
                .forEach(session::addBootstrapListener);
    }

    GuiceViewProvider getViewProvider() {
        return viewProvider;
    }

    GuiceUIProvider getGuiceUIProvider() {
        return guiceUIProvider;
    }

    UIScope getUiScoper() {
        return uiScoper;
    }

    Set<Class<? extends View>> getViews() {
        return views;
    }

    Set<Class<? extends UI>> getUis() {
        return uis;
    }

    Set<Class<? extends ViewChangeListener>> getViewChangeListeners(Class<? extends UI> uiClass) {
        return viewChangeListeners.get(uiClass);
    }

    VaadinSessionScope getVaadinSessionScoper() {
        return vaadinSessionScoper;
    }

    Iterator<VaadinServiceInitListener> getServiceInitListeners() {
        return vaadinServiceInitListeners
                .stream()
                .map(key -> (VaadinServiceInitListener) getInjector().getInstance(key))
                .iterator();
    }

    private Module createModule(Class<? extends Module> moduleClass, Reflections reflections, Annotation annotation) {

        for (Constructor<?> constructor : moduleClass.getDeclaredConstructors()) {

            Object[] initArgs = new Object[constructor.getParameterCount()];

            Class<?>[] parameterTypes = constructor.getParameterTypes();

            boolean allParameterTypesResolved = true;

            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];

                if (Reflections.class.equals(parameterType)) {
                    initArgs[i] = reflections;
                } else if (injectorProviderType.equals(parameterType)) {
                    initArgs[i] = (Provider<Injector>) this::getInjector;
                } else if (annotation != null && annotation.getClass().equals(parameterType)) {
                    initArgs[i] = annotation;
                } else {
                    allParameterTypesResolved = false;
                    break;
                }
            }

            if (!allParameterTypesResolved) {
                continue;
            }

            constructor.setAccessible(true);

            try {
                return (Module) constructor.newInstance(initArgs);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        throw new IllegalStateException("no suitable constructor found for %s" + moduleClass);
    }

    Injector getInjector() {
        return checkNotNull(injector, "injector is not set up yet");
    }

    boolean isNavigable(Class<? extends UI> uiClass, Class<? extends View> viewClass) {
        checkNotNull(viewClass);

        if (!uis.contains(uiClass)) {
            //unknown ui, not navigable
            return false;
        }

        ForUI forUI = viewClass.getAnnotation(ForUI.class);

        if (forUI == null) {
            //no @ForUI-annotation means that the view is not restricted to a particular set of UI's
            return true;
        }

        final List<Class<? extends UI>> applicableUIs = Arrays.asList(forUI.value());

        checkArgument(!applicableUIs.isEmpty(), "@ForUI#value() must not be empty at %s", viewClass);

        return applicableUIs.contains(uiClass);
    }
}
