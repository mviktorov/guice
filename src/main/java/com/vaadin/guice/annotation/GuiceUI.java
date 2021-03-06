package com.vaadin.guice.annotation;

import com.vaadin.guice.server.GuiceNavigator;
import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewProvider;
import com.vaadin.server.ErrorHandler;
import com.vaadin.ui.Component;
import com.vaadin.ui.UI;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation to be put on {@link com.vaadin.ui.UI}-subclasses that are to be automatically detected
 * and configured by guice. Use it like this: <p>
 * <pre>
 * &#064;GuiceUI
 * public class MyRootUI extends UI {
 *     // ...
 * }
 * </pre>
 * <p> Or like this, if you want to map your UI to another URL (for example if you are having
 * multiple UI subclasses in your application): <p>
 * <pre>
 * &#064;GuiceUI(path = &quot;/myPath&quot;)
 * public class MyUI extends UI {
 *     // ...
 * }
 * </pre>
 *
 * @author Petter Holmström (petter@vaadin.com)
 * @author Bernd Hopp (bernd@vaadin.com)
 */
@Target({java.lang.annotation.ElementType.TYPE})
@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@Documented
public @interface GuiceUI {

    /**
     * The path to which the UI will be bound. For example, a value of {@code "/myUI"} would be
     * mapped to {@code "/myContextPath/myVaadinServletPath/myUI"}. An empty string (default) will
     * map the UI to the root of the servlet. Within a web application, there must not be multiple
     * UI sub classes with the same path.
     */
    String path() default "";

    /**
     * The actual content of the UI.
     *
     * @see UI#setContent
     */
    Class<? extends Component> content() default Component.class;

    /**
     * The UI's view container for navigation. ViewContainers are used by a UI's {@link
     * com.vaadin.navigator.Navigator}. A view container must implement one of the following
     * interface: <p> <ul> <li>{@link com.vaadin.ui.ComponentContainer}</li> <li>{@link
     * com.vaadin.ui.SingleComponentContainer}</li> <li>{@link com.vaadin.navigator.ViewDisplay}</li>
     * </ul> <p>
     */
    Class<? extends Component> viewContainer() default Component.class;

    /**
     * the {@link Navigator} that is to be used for navigation
     */
    Class<? extends GuiceNavigator> navigator() default GuiceNavigator.class;

    /**
     * the error-View
     *
     * @see Navigator#setErrorProvider(ViewProvider)
     */
    Class<? extends View> errorView() default View.class;

    /**
     * the {@link UI}'s error-handler
     *
     * @see com.vaadin.server.AbstractClientConnector#setErrorHandler(ErrorHandler)
     */
    Class<? extends ErrorHandler> errorHandler() default ErrorHandler.class;
}
