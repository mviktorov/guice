package com.vaadin.guice.server;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import com.vaadin.guice.annotation.GuiceView;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewProvider;
import com.vaadin.server.ServiceException;
import com.vaadin.server.SessionDestroyEvent;
import com.vaadin.server.SessionDestroyListener;
import com.vaadin.server.SessionInitEvent;
import com.vaadin.server.SessionInitListener;
import com.vaadin.server.VaadinSession;
import com.vaadin.ui.UI;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.vaadin.guice.server.PathUtil.removeParametersFromViewName;
import static java.lang.Character.isUpperCase;
import static java.lang.Character.toLowerCase;

/**
 * A Vaadin {@link ViewProvider} that fetches the views from the guice application context. The
 * views must implement the {@link View} interface and be annotated with the {@link GuiceView}
 * annotation. <p>
 *
 * @author Petter Holmström (petter@vaadin.com)
 * @author Henri Sara (hesara@vaadin.com)
 * @author Bernd Hopp (bernd@vaadin.com)
 * @see GuiceView
 */
class GuiceViewProvider implements ViewProvider, SessionDestroyListener, SessionInitListener {

    private static final long serialVersionUID = 6113953554214462809L;

    private final Map<String, Class<? extends View>> viewNamesToViewClassesMap;
    private final GuiceVaadin guiceVaadin;
    private final Map<VaadinSession, Map<UI, Map<String, View>>> viewsBySessionMap;
    private final NavigableSet<String> viewNames;

    GuiceViewProvider(Set<Class<? extends View>> viewClasses, GuiceVaadin guiceVaadin) {

        viewNamesToViewClassesMap = scanForViews(viewClasses);
        this.guiceVaadin = guiceVaadin;
        // Set of view names sorted by their natural ordering (lexicographic).
        // This is useful for quickly looking up views by name
        viewNames = ImmutableSortedSet.copyOf(viewNamesToViewClassesMap.keySet());

        viewsBySessionMap = new ConcurrentHashMap<>();
    }

    private Map<String, Class<? extends View>> scanForViews(Set<Class<? extends View>> viewClasses) {
        ImmutableMap.Builder<String, Class<? extends View>> viewMapBuilder = ImmutableMap.builder();

        for (Class<? extends View> viewClass : viewClasses) {

            GuiceView annotation = viewClass.getAnnotation(GuiceView.class);

            checkState(annotation != null);

            if (GuiceView.USE_CONVENTIONS.equals(annotation.name())) {

                String className = viewClass.getSimpleName();

                StringBuilder viewNameBuilder = new StringBuilder();

                for (int i = 0; i < className.length(); i++) {
                    char c = className.charAt(i);

                    if (isUpperCase(c)) {
                        if (i != 0) {
                            viewNameBuilder.append('-');
                        }
                        viewNameBuilder.append(toLowerCase(c));
                    } else {
                        viewNameBuilder.append(c);
                    }
                }

                viewMapBuilder.put(viewNameBuilder.toString(), viewClass);
            } else {
                viewMapBuilder.put(annotation.name(), viewClass);
            }
        }

        return viewMapBuilder.build();
    }

    @Override
    public String getViewName(String viewAndParameters) {

        final String viewName = removeParametersFromViewName(viewAndParameters);

        return viewNames.contains(viewName) ? viewName : null;
    }

    @Override
    public View getView(String viewName) {
        checkArgument(viewNames.contains(viewName), "%s is not a registered view-name", viewName);

        VaadinSession session = VaadinSession.getCurrent();

        Map<UI, Map<String, View>> viewsByUI = viewsBySessionMap.get(session);

        Map<String, View> views = viewsByUI.computeIfAbsent(guiceVaadin.getCurrentUIProvider().get(), k -> new HashMap<>(viewNames.size()));

        View view = views.get(viewName);

        if (view == null) {
            Class<? extends View> viewClass = viewNamesToViewClassesMap.get(viewName);

            checkArgument(viewClass != null, "no view for name %s registered", viewName);

            synchronized (guiceVaadin) {
                try {
                    guiceVaadin.getViewScoper().startInitialization();

                    view = guiceVaadin.assemble(viewClass);
                    views.put(viewName, view);

                    guiceVaadin.getViewScoper().endInitialization(view);
                } catch (RuntimeException e) {
                    guiceVaadin.getViewScoper().rollbackInitialization();
                    throw e;
                }
            }
        }

        return view;
    }

    @Override
    public void sessionDestroy(SessionDestroyEvent event) {
        viewsBySessionMap.remove(event.getSession());
    }

    @Override
    public void sessionInit(SessionInitEvent event) throws ServiceException {
        viewsBySessionMap.put(event.getSession(), new ConcurrentHashMap<>());
    }
}
