package com.vaadin.guice.server;

import com.google.inject.Binding;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.spi.ProvisionListener;

import com.vaadin.guice.annotation.GuiceUI;
import com.vaadin.guice.annotation.UIScope;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewDisplay;
import com.vaadin.navigator.ViewProvider;
import com.vaadin.server.ErrorHandler;
import com.vaadin.ui.Component;
import com.vaadin.ui.ComponentContainer;
import com.vaadin.ui.SingleComponentContainer;
import com.vaadin.ui.UI;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.lang.reflect.Modifier.isAbstract;

class UIProvider extends AbstractMatcher<Binding<?>> implements ProvisionListener {
    private final GuiceVaadinServlet guiceVaadinServlet;

    public UIProvider(GuiceVaadinServlet guiceVaadinServlet) {
        this.guiceVaadinServlet = guiceVaadinServlet;
    }

    @Override
    public boolean matches(Binding<?> binding) {
        final Class<?> rawType = binding.getKey().getTypeLiteral().getRawType();

        return UI.class.isAssignableFrom(rawType) && !isAbstract(rawType.getModifiers());
    }

    @Override
    public <T> void onProvision(ProvisionInvocation<T> provisionInvocation) {
        UI ui = (UI) provisionInvocation.provision();

        final Class<? extends UI> uiClass = ui.getClass();

        GuiceUI annotation = uiClass.getAnnotation(GuiceUI.class);

        checkState(annotation != null);

        Class<? extends Component> viewContainerClass = annotation.viewContainer();

        if (!Component.class.equals(viewContainerClass)) {

            checkState(
                    viewContainerClass.isAnnotationPresent(UIScope.class),
                    "%s is annotated with having %s as it's viewContainer, but this class does not have a @UIScope annotation. " +
                            "ViewContainers must be put in UIScope",
                    uiClass, viewContainerClass
            );

            Component defaultView = guiceVaadinServlet.getInjector().getInstance(viewContainerClass);

            GuiceNavigator navigator = guiceVaadinServlet.getInjector().getInstance(annotation.navigator());

            if (defaultView instanceof ViewDisplay) {
                navigator.init(ui, (ViewDisplay) defaultView);
            } else if (defaultView instanceof ComponentContainer) {
                navigator.init(ui, (ComponentContainer) defaultView);
            } else if (defaultView instanceof SingleComponentContainer) {
                navigator.init(ui, (SingleComponentContainer) defaultView);
            } else {
                throw new IllegalArgumentException(
                        format(
                                "%s is set as viewContainer() in @GuiceUI of %s, must be either ComponentContainer, SingleComponentContainer or ViewDisplay",
                                viewContainerClass,
                                uiClass
                        )
                );
            }

            if (!ViewProvider.class.equals(annotation.errorProvider())) {
                checkArgument(annotation.errorView().equals(View.class), "GuiceUI#errorView and GuiceUI#errorProvider cannot be set both");

                final ViewProvider errorProvider = guiceVaadinServlet.getInjector().getInstance(annotation.errorProvider());

                navigator.setErrorProvider(errorProvider);
            } else if (!View.class.equals(annotation.errorView())) {
                navigator.setErrorView(annotation.errorView());
            }

            guiceVaadinServlet
                    .getViewChangeListeners(uiClass)
                    .stream()
                    .map(guiceVaadinServlet.getInjector()::getInstance)
                    .forEach(navigator::addViewChangeListener);

            navigator.addProvider(guiceVaadinServlet.getViewProvider());

            ui.setNavigator(navigator);
        }

        Class<? extends Component> contentClass = annotation.content();

        if (!Component.class.equals(contentClass)) {
            checkState(
                    contentClass.isAnnotationPresent(com.vaadin.guice.annotation.UIScope.class),
                    "%s is annotated with having %s as it's viewContainer, but this class does not have a @UIScope annotation. " +
                            "ViewContainers must be put in UIScope",
                    uiClass, contentClass
            );

            Component content = guiceVaadinServlet.getInjector().getInstance(contentClass);

            ui.setContent(content);
        }

        if (!ErrorHandler.class.equals(annotation.errorHandler())) {
            ErrorHandler errorHandler = guiceVaadinServlet.getInjector().getInstance(annotation.errorHandler());

            ui.setErrorHandler(errorHandler);
        }

        guiceVaadinServlet.getInjector().injectMembers(ui);
    }
}