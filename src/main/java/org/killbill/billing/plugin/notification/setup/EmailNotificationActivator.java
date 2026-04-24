/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2020 Equinix, Inc
 * Copyright 2014-2021 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.notification.setup;

import java.sql.SQLException;
import java.util.Hashtable;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.flywaydb.core.Flyway;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillEventDispatcher;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.dao.PluginDao;
import org.killbill.billing.plugin.dao.PluginDao.DBEngine;
import org.killbill.billing.plugin.notification.api.InvoiceFormatterFactory;
import org.killbill.billing.plugin.notification.dao.ConfigurationDao;
import org.killbill.billing.plugin.notification.http.EmailNotificationServlet;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmailNotificationActivator extends KillbillActivatorBase {

    private static final Logger logger = LoggerFactory.getLogger(EmailNotificationActivator.class);

    public static final String PLUGIN_NAME = "killbill-email-notifications";
    public static final String PROPERTY_PREFIX = "org.killbill.billing.plugin.email-notifications.";

    private OSGIKillbillEventDispatcher.OSGIKillbillEventHandler emailNotificationListener;
    private EmailNotificationConfigurationHandler emailNotificationConfigurationHandler;
    private ServiceTracker<InvoiceFormatterFactory, InvoiceFormatterFactory> invoiceFormatterTracker;

    @Override
    public void start(final BundleContext context) throws Exception {
        super.start(context);

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        runMigrationsIfEnabled();

        // Register an event listener for plugin configuration (optional)
        emailNotificationConfigurationHandler = new EmailNotificationConfigurationHandler(region, PLUGIN_NAME, killbillAPI, dataSource);
        final EmailNotificationConfiguration globalConfiguration = emailNotificationConfigurationHandler.createConfigurable(configProperties.getProperties());
        emailNotificationConfigurationHandler.setDefaultConfigurable(globalConfiguration);

        // create a service tracker for a custom InvoiceFormatter service
        invoiceFormatterTracker = new ServiceTracker<>(context, InvoiceFormatterFactory.class, null);
        invoiceFormatterTracker.open();

        // Register an event listener (optional)
        emailNotificationListener = new EmailNotificationListener(clock, killbillAPI, configProperties, dataSource, emailNotificationConfigurationHandler, invoiceFormatterTracker);

        final ConfigurationDao configurationDao = new ConfigurationDao(dataSource.getDataSource());

        // Register a servlet (optional)
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillAPI,
                                                         dataSource,
                                                         super.clock,
                                                         configProperties).withRouteClass(EmailNotificationServlet.class)
                                                                          .withService(configurationDao)
                                                                          .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerServlet(context, httpServlet);
        registerHandlers();
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        super.stop(context);

        if (invoiceFormatterTracker != null) {
            invoiceFormatterTracker.close();
        }
    }


    private void registerHandlers() {

        final PluginConfigurationEventHandler configHandler = new PluginConfigurationEventHandler(emailNotificationConfigurationHandler);

        dispatcher.registerEventHandlers(configHandler,
                new OSGIKillbillEventDispatcher.OSGIFrameworkEventHandler() {
                    @Override
                    public void started() {
                        dispatcher.registerEventHandlers(emailNotificationListener);
                    }
                });
    }

    private void registerServlet(final BundleContext context, final HttpServlet servlet) {
        final Hashtable<String, String> props = new Hashtable<String, String>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, Servlet.class, servlet, props);
    }

    private void runMigrationsIfEnabled() {
        if (EmailNotificationConfiguration.shouldRunMigrations(configProperties.getProperties())) {
            DBEngine dbEngine;
            try {
                dbEngine = PluginDao.getDBEngine(dataSource.getDataSource());
            } catch (final SQLException e) {
                logger.warn("Unable to determine database engine, defaulting to MySQL migrations", e);
                dbEngine = DBEngine.MYSQL;
            }

            final String locations;
            switch (dbEngine) {
                case POSTGRESQL:
                    locations = "classpath:migration/postgresql";
                    break;
                case GENERIC:
                case H2:
                case MYSQL:
                default:
                    // H2 and GENERIC use MySQL-compatible migration scripts
                    locations = "classpath:migration/mysql";
                    break;
            }

            final Flyway flyway = Flyway.configure(getClass().getClassLoader())
                                        .dataSource(dataSource.getDataSource())
                                        .locations(locations)
                                        .table("email_notifications_schema_history")
                                        .baselineOnMigrate(true)
                                        .baselineVersion("0")
                                        .load();
            flyway.migrate();
        } else {
            logger.info("Skipping Flyway migrations as '{}' is not set to true",
                        "org.killbill.billing.plugin.emailnotifications.shouldRunMigrations");
        }
    }

}
