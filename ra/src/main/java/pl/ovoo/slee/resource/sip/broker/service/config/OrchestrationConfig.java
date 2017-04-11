/*
 * SIP Service Broker Resource Adaptor
 * Copyright (C) 2016-2017 "OVOO Sp. z o.o."
 *
 * This file is part of the SIP Service Broker RA.
 *
 * SIP Service Broker RA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SIP Service Broker RA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pl.ovoo.slee.resource.sip.broker.service.config;

import org.slf4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

/**
 */
public class OrchestrationConfig {

    private static final String ELEM_BROKER_CONFIG = "broker-config";
    private static final String ELEM_SERVICES = "services";
    private static final String ELEM_APPLICATION = "application";
    private static final String ELEM_ENDPOINT = "endpoint";
    private static final String ELEM_ORCHESTRATION_RULES = "orchestration-rules";
    private static final String ELEM_ORCHESTRATION_RULESET = "orchestration-ruleset";
    private static final String ELEM_SERVICE = "service";
    private static final String ELEM_ERROR_RESPONSES_TO_SKIP = "error-responses-to-skip-service";
    private static final String ELEM_ERROR_RESPONSES_TO_STOP = "error-responses-to-stop-orchestration";
    private static final String ELEM_RESPONSE_CODE = "response-code";

    private static final String ATTR_ALIAS = "alias";
    private static final String ATTR_EXTERNAL = "external";
    private static final String ATTR_SERVICE_KEY = "servicekey";
    private static final String ATTR_DEFAULT_ERROR_HANDLING = "defaultErrorResponseHandling";


    // Service keys to orchestration rules map
    private final Map<String, OrchestrationRuleset> rulesMap = new HashMap<>();

    // Application aliases to ApplicationServie map (contains list of endpoints, statuses, etc)
    private final Map<String, OrchestratedService> applicationServices = new HashMap<>();

    private final Logger logger;

    public OrchestrationConfig(Logger logger) {
        this.logger = logger;
    }

    /**
     * Parses configuration xml and stores configuration data structures
     * @param configFile - file to parse
     * @param addressFactory - SIP address factory (create endpoints' addresses)
     * @param headerFactory  - SIP header factory
     */
    public void loadConfig(String configFile, AddressFactory addressFactory, HeaderFactory headerFactory) {
        try{
            File inputFile = new File(configFile);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Element brokerConfig = dBuilder.parse(inputFile).getDocumentElement();
            brokerConfig.normalize();
            String nodeName = brokerConfig.getNodeName();
            if (!ELEM_BROKER_CONFIG.equals(nodeName)) {
                throw new IllegalArgumentException("Unexpected top element: " + nodeName + " expected " +
                        ELEM_BROKER_CONFIG);
            }

            loadServices(brokerConfig, addressFactory, headerFactory);
            loadOrchestrationRules(brokerConfig);
        } catch (ParserConfigurationException | IOException | SAXException | ParseException e){
            throw new IllegalArgumentException("Unable to load configuration from file", e);
        }
    }

    /*
     * Reads services definitions from the configuration
     */
    private void loadServices(Element brokerConfig, AddressFactory addressFactory, HeaderFactory headerFactory)
            throws ParseException {
        NodeList nList = brokerConfig.getElementsByTagName(ELEM_SERVICES);
        if (nList.getLength() != 1) {
            throw new IllegalArgumentException("Broker configuration error: wrong number of services in xml");
        }
        Element servicesElem = (Element) nList.item(0);
        NodeList servicesList = servicesElem.getElementsByTagName(ELEM_APPLICATION);

        for (int i = 0; i < servicesList.getLength(); i++) {
            Element applicationElem = (Element) servicesList.item(i);
            OrchestratedService application = new OrchestratedService(applicationElem.getAttribute(ATTR_ALIAS),
                                        Boolean.valueOf(applicationElem.getAttribute(ATTR_EXTERNAL)));

            NodeList endpoints = applicationElem.getElementsByTagName(ELEM_ENDPOINT);
            for (int j = 0; j < endpoints.getLength(); j++) {
                Element endpointElement = (Element) endpoints.item(j);
                String endpointVal = endpointElement.getTextContent();
                if (!"" .equals(endpointVal)) {
                    Endpoint endpoint = new Endpoint(endpointVal, application.getAlias(), addressFactory,headerFactory);
                    application.addEndpoint(endpoint);
                }
            }

            applicationServices.put(application.getAlias(), application);
            logger.trace("Found config: {}", application);
        }
    }

    /*
     * Reads orchestration rulesets from the configuration
     */
    private void loadOrchestrationRules(Element brokerConfig) throws IllegalArgumentException {
        Element rulesElem = getSingleElement(brokerConfig, ELEM_ORCHESTRATION_RULES);
        NodeList rulesList = rulesElem.getElementsByTagName(ELEM_ORCHESTRATION_RULESET);
        for (int i = 0; i < rulesList.getLength(); i++) {

            Element ruleElem = (Element) rulesList.item(i);
            OrchestrationRuleset ruleset = new OrchestrationRuleset(readMandatoryAttribute(ruleElem, ATTR_SERVICE_KEY));

            String handlingString = readMandatoryAttribute(ruleElem, ATTR_DEFAULT_ERROR_HANDLING);
            ruleset.setDefaultErrorResponseHandling(OrchestrationRuleset.ErrorLogic.valueOf(handlingString));

            NodeList serviceList = ruleElem.getElementsByTagName(ELEM_SERVICE);
            for (int j = 0; j < serviceList.getLength(); j++) {
                Element service = (Element) serviceList.item(j);
                String serviceAlias = service.getTextContent();
                if (!"" .equals(serviceAlias)) {
                    ruleset.appendApplication(applicationServices.get(serviceAlias));
                }
            }

            readErrorResponseHandling(ruleElem, ruleset);

            rulesMap.put(ruleset.getServiceKey(), ruleset);
            logger.trace("Found config: {}", ruleset);
        }
    }

    /*
     * Retrieves single Element from the parent
     */
    private Element getSingleElement(Element parent, String name) {
        NodeList listOfChildren = parent.getElementsByTagName(name);
        if (listOfChildren == null || listOfChildren.getLength() != 1) {
            throw new IllegalArgumentException("Broker config error: single " + name + " expected within " + parent
                    .getNodeName());
        }
        return (Element) listOfChildren.item(0);
    }

    /*
     * Reads mandatory attribute from element or throws IllegalArgumentException if not found
     */
    private String readMandatoryAttribute(Element element, String name) {

        String attribute = element.getAttribute(name);
        if (attribute != null && attribute.length() > 0) {
            return attribute;
        }
        throw new IllegalArgumentException("Broker config error: missing mandatory attribute [" + name + "] within "
                + element.getNodeName());
    }

    /*
     * Reads mapping of SIP error responses to orchestration handling.
     */
    private void readErrorResponseHandling(Element orchestrationRulesetElement, OrchestrationRuleset ruleset) {

        Element skipResponsesElem = getSingleElement(orchestrationRulesetElement, ELEM_ERROR_RESPONSES_TO_SKIP);
        NodeList skipResponsesList = skipResponsesElem.getElementsByTagName(ELEM_RESPONSE_CODE);
        for (int j = 0; j < skipResponsesList.getLength(); j++) {
            Element responseElem = (Element) skipResponsesList.item(j);
            String responseCode = responseElem.getTextContent();
            if (!"" .equals(responseCode)) {
                ruleset.addErrorResponseHandling(Integer.valueOf(responseCode), OrchestrationRuleset.ErrorLogic.SKIP);
            }
        }

        Element stopResponsesElem = getSingleElement(orchestrationRulesetElement, ELEM_ERROR_RESPONSES_TO_STOP);
        NodeList stopResponsesList = stopResponsesElem.getElementsByTagName(ELEM_RESPONSE_CODE);
        for (int j = 0; j < stopResponsesList.getLength(); j++) {
            Element responseElem = (Element) stopResponsesList.item(j);
            String responseCode = responseElem.getTextContent();
            if (!"" .equals(responseCode)) {
                ruleset.addErrorResponseHandling(Integer.valueOf(responseCode), OrchestrationRuleset.ErrorLogic.STOP);
            }
        }
    }

    public OrchestrationRuleset getRulesForKey(String key) {
        return rulesMap.get(key);
    }

    public OrchestratedService getApplicationServiceForAlias(String alias){
        return applicationServices.get(alias);
    }

}
