package com.vaadin.guice.server;

import com.vaadin.guice.annotation.GuiceUI;
import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.ViewDisplay;
import com.vaadin.ui.ComponentContainer;
import com.vaadin.ui.SingleComponentContainer;
import com.vaadin.ui.UI;

/**
 * GuiceNavigator is the default {@link Navigator} for GuiceVaadinServlet
 *
 * @see Navigator
 * @see GuiceUI#navigator()
 */
public class GuiceNavigator extends Navigator {

    void init(UI ui, ComponentContainer container) {
        init(ui, new ComponentContainerViewDisplay(container));
    }

    void init(UI ui, SingleComponentContainer container) {
        init(ui, new SingleComponentContainerViewDisplay(container));
    }

    void init(UI ui, ViewDisplay display) {
        init(ui, new UriFragmentManager(ui.getPage()), display);
    }
}
