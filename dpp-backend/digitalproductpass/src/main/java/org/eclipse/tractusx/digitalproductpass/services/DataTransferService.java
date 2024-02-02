/*********************************************************************************
 *
 * Catena-X - Digital Product Pass Backend
 *
 * Copyright (c) 2022, 2024 BASF SE, BMW AG, Henkel AG & Co. KGaA
 * Copyright (c) 2022, 2024 Contributors to the Eclipse Foundation
 *
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the
 * License for the specific language govern in permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.digitalproductpass.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.tractusx.digitalproductpass.config.DtrConfig;
import org.eclipse.tractusx.digitalproductpass.exceptions.ControllerException;
import org.eclipse.tractusx.digitalproductpass.exceptions.ServiceException;
import org.eclipse.tractusx.digitalproductpass.exceptions.ServiceInitializationException;
import org.eclipse.tractusx.digitalproductpass.managers.ProcessDataModel;
import org.eclipse.tractusx.digitalproductpass.managers.ProcessManager;
import org.eclipse.tractusx.digitalproductpass.models.catenax.Dtr;
import org.eclipse.tractusx.digitalproductpass.models.http.requests.Search;
import org.eclipse.tractusx.digitalproductpass.models.http.responses.IdResponse;
import org.eclipse.tractusx.digitalproductpass.models.manager.History;
import org.eclipse.tractusx.digitalproductpass.models.manager.Status;
import org.eclipse.tractusx.digitalproductpass.models.negotiation.*;
import org.eclipse.tractusx.digitalproductpass.models.negotiation.Set;
import org.eclipse.tractusx.digitalproductpass.models.service.BaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import utils.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * This class consists exclusively of methods to operate on executing the Data Transfer operations.
 *
 * <p> The methods and inner classes defined here are intended to do every needed operations in order to be able to Transfer the Passport Data of a given request.
 *
 */
@Service
public class DataTransferService extends BaseService {

    /** ATTRIBUTES **/
    private final HttpUtil httpUtil;
    private final JsonUtil jsonUtil;
    public String apiKey;
    public String bpnNumber;
    public String edcEndpoint;
    public String managementPath;
    public String catalogPath;
    public String negotiationPath;
    public String transferPath;

    public Environment env;
    public ProcessManager processManager;

    public DtrConfig dtrConfig;

    /** CONSTRUCTOR(S) **/
    @Autowired
    public DataTransferService(Environment env, HttpUtil httpUtil, JsonUtil jsonUtil, VaultService vaultService, ProcessManager processManager, DtrConfig dtrConfig) throws ServiceInitializationException {
        this.httpUtil = httpUtil;
        this.jsonUtil = jsonUtil;
        this.processManager = processManager;
        this.dtrConfig = dtrConfig;
        this.env = env;
        this.init(vaultService, env);
        this.checkEmptyVariables(List.of("apiKey")); // Add API Key as optional for initialization
    }

    /** METHODS **/

    /**
     * Initiates the main needed variables for Data Transfer Service by loading from the environment variables and Vault.
     **/
    public void init(VaultService vaultService, Environment env) {
        this.apiKey = (String) vaultService.getLocalSecret("edc.apiKey");
        this.bpnNumber = (String) vaultService.getLocalSecret("edc.participantId");
        this.edcEndpoint = env.getProperty("configuration.edc.endpoint", "");
        this.catalogPath = env.getProperty("configuration.edc.catalog", "");
        this.managementPath = env.getProperty("configuration.edc.management", "");
        this.negotiationPath = env.getProperty("configuration.edc.negotiation", "");
        this.transferPath = env.getProperty("configuration.edc.transfer", "");
    }

    /**
     * Creates a List of missing variables needed to proceed with the request.
     * <p>
     *
     * @return an {@code Arraylist} with the environment variables missing in the configuration for the request.
     *
     */
    @Override
    public List<String> getEmptyVariables() {
        List<String> missingVariables = new ArrayList<>();
        if (this.edcEndpoint == null || this.edcEndpoint.isEmpty()) {
            missingVariables.add("endpoint");
        }
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            missingVariables.add("apiKey");
        }
        if (this.bpnNumber == null || this.bpnNumber.isEmpty()) {
            missingVariables.add("bpnNumber");
        }
        if (this.managementPath == null || this.managementPath.isEmpty()) {
            missingVariables.add("management");
        }
        if (this.catalogPath == null || this.catalogPath.isEmpty()) {
            missingVariables.add("catalog");
        }
        if (this.negotiationPath == null || this.negotiationPath.isEmpty()) {
            missingVariables.add("negotiation");
        }
        if (this.transferPath == null || this.transferPath.isEmpty()) {
            missingVariables.add("transfer");
        }

        return missingVariables;
    }

    /**
     * Checks the EDC consumer connection by trying to establish a connection and retrieve an empty catalog.
     * <p>
     *
     * @return a {@code String} participantId of the retrieved catalog.
     *
     * @throws  ControllerException
     *           if unable to check the EDC consumer connection.
     */
    public String checkEdcConsumerConnection() throws ServiceException {
        try {
            String edcConsumerDsp = this.edcEndpoint + CatenaXUtil.edcDataEndpoint;
            Catalog catalog = this.getContractOfferCatalog(edcConsumerDsp, ""); // Get empty catalog
            if (catalog == null || catalog.getParticipantId().isEmpty()) {
                throw new ControllerException(this.getClass().getName()+".checkEdcConsumerConnection", "The catalog response is null or the participant id is not set!");
            }
            return catalog.getParticipantId();
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName()+".checkEdcConsumerConnection", e, "It was not possible to establish connection with the EDC consumer endpoint [" + this.edcEndpoint+"]");
        }
    }

    /**
     * Gets the Contract Offer from the given AssetId in the given provider URL.
     * <p>
     * @param   assetId
     *          the {@code String} identification of the EDC's asset to lookup for.
     * @param   providerUrl
     *          the {@code String} provider URL of the asset.
     *
     * @return  a {@code Dataset} object with the contract offer information.
     *
     * @throws  ControllerException
     *           if unable to get the contract offer for the assetId.
     */
    public Dataset getContractOfferByAssetId(String assetId, String providerUrl) throws ServiceException {
        /*
         *   This method receives the assetId and looks up for targets with the same name.
         */
        try {
            Catalog catalog = this.getContractOfferCatalog(providerUrl, assetId);
            if(catalog == null){
                return null;
            }
            Object offers = catalog.getContractOffers();
            if(offers == null){
                return null;
            }
            if(catalog.getContractOffers() instanceof LinkedHashMap){
                return (Dataset) jsonUtil.bindObject(offers, Dataset.class);
            }

            List<Dataset> contractOffers = (List<Dataset>) jsonUtil.bindObject(offers, List.class);
            if(contractOffers.size() == 0){
                return null;
            }
            int i = 0;
            Map<String, Integer> contractOffersMap = new HashMap<>();
            for(Dataset offer: contractOffers){
                contractOffersMap.put(offer.getAssetId(),i);
                i++;
            }
            if(!contractOffersMap.containsKey(assetId))
            {
                return null;
            }
            Integer index = contractOffersMap.get(assetId);
            return contractOffers.get(index);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName(), e, "It was not possible to get Contract Offer for assetId [" + assetId + "]");
        }
    }

    /**
     * Builds a negotiation request with the given data.
     * <p>
     * @param   dataset
     *          the {@code Dataset} data for the contract offer.
     * @param   status
     *          the {@code Status} status of the process.
     * @param   bpn
     *          the {@code String} BPN number from BNP discovery for the request.
     *
     * @return  a {@code NegotiationRequest} object with the given data.
     *
     */
    public NegotiationRequest buildRequest(Dataset dataset, Status status, String bpn) {
        Offer contractOffer = this.buildOffer(dataset, 0);
        return new NegotiationRequest(
                jsonUtil.toJsonNode(Map.of("odrl", "http://www.w3.org/ns/odrl/2/")),
                status.getEndpoint(),
                bpn,
                contractOffer
        );
    }

    /**
     * Builds a negotiation request with the given data.
     * <p>
     * @param   dataset
     *          the {@code Dataset} data for the offer.
     * @param   defaultIndex
     *          the {@code Integer} default index for the policy.
     *
     * @return  a {@code Offer} object with the given data built offer.
     *
     */
    public Offer buildOffer(Dataset dataset, Integer defaultIndex) {
        Object rawPolicy = dataset.getPolicy();
        Set policy = null;
        if(rawPolicy instanceof LinkedHashMap){
            policy = (Set) jsonUtil.bindObject(rawPolicy, Set.class);
        }else{
            List<LinkedHashMap> policyList = (List<LinkedHashMap>) jsonUtil.bindObject(rawPolicy, List.class);
            policy = (Set) jsonUtil.bindObject(policyList.get(defaultIndex), Set.class); // Get fist policy from the list to resolve the conflict
        }
        Set policyCopy = (Set) jsonUtil.bindObject(policy, Set.class);
        policyCopy.setId(null);
        return new Offer(
                policy.getId(),
                dataset.getAssetId(),
                policyCopy
        );
    }

    /**
     * Gets the Contract Offer's Catalog from the provider.
     * <p>
     * @param   providerUrl
     *          the {@code String} URL from the provider.
     * @param   assetId
     *          the {@code String} identification of the EDC's asset.
     *
     * @return  a {@code Catalog} object for the given AssetId .
     *
     * @throws  ServiceException
     *           if unable to retrieve the catalog.
     */
    public Catalog getContractOfferCatalog(String providerUrl, String assetId) {
        try {
            this.checkEmptyVariables();

            String url = CatenaXUtil.buildManagementEndpoint(env, this.catalogPath);
            // Simple catalog request query with no limitation.
            CatalogRequest.QuerySpec querySpec = new CatalogRequest.QuerySpec();
            CatalogRequest.QuerySpec.FilterExpression filterExpression = new CatalogRequest.QuerySpec.FilterExpression(
                    "https://w3id.org/edc/v0.0.1/ns/id",
                    "=",
                    assetId
            ); // Filter by asset id
            querySpec.setFilterExpression(List.of(filterExpression));
            Object body = new CatalogRequest(
                    jsonUtil.newJsonNode(),
                    providerUrl,
                    querySpec
            );
            HttpHeaders headers = httpUtil.getHeaders();
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            ResponseEntity<?> response = httpUtil.doPost(url, JsonNode.class, headers, httpUtil.getParams(), body, false, false);
            JsonNode result = (JsonNode) response.getBody();
            return (Catalog) jsonUtil.bindJsonNode(result, Catalog.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getContractOfferCatalog",
                    e,
                    "It was not possible to retrieve the catalog!");
        }
    }

    /**
     * Searches for the Digital Twin's Catalog from the provider.
     * <p>
     * @param   providerUrl
     *          the {@code String} URL from the provider.
     *
     * @return  a {@code Catalog} object of the given provider, if exists.
     *
     * @throws  ServiceException
     *           if unable to retrieve the catalog.
     */
    public Catalog searchDigitalTwinCatalog(String providerUrl) throws ServiceException {
        try {
            this.checkEmptyVariables();

            String url = CatenaXUtil.buildManagementEndpoint(env, this.catalogPath);
            // Simple catalog request query with no limitation.
            CatalogRequest.QuerySpec querySpec = new CatalogRequest.QuerySpec();
            CatalogRequest.QuerySpec.FilterExpression filterExpression = new CatalogRequest.QuerySpec.FilterExpression(
                    "https://w3id.org/edc/v0.0.1/ns/type",
                    "=",
                    this.dtrConfig.getAssetType()
            ); // Filter by asset id
            querySpec.setFilterExpression(List.of(filterExpression));
            Object body = new CatalogRequest(
                    jsonUtil.newJsonNode(),
                    CatenaXUtil.buildDataEndpoint(providerUrl),
                    querySpec
            );

            HttpHeaders headers = httpUtil.getHeaders();
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            ResponseEntity<?> response = httpUtil.doPost(url, JsonNode.class, headers, httpUtil.getParams(), body, false, false);
            if(response == null){
                return null;
            }
            JsonNode result = (JsonNode) response.getBody();
            return (Catalog) jsonUtil.bindJsonNode(result, Catalog.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getContractOfferCatalog",
                    e,
                    "It was not possible to retrieve the catalog!");
        }
    }

    /**
     * Initiates the Contract Negotiation in order to retrieve it.
     * <p>
     * @param   negotiationRequest
     *          the {@code NegotiationRequest} object with negotiation request data.
     *
     * @return  a {@code IdResponse} object with the contract negotiation response.
     *
     * @throws  ServiceException
     *           if unable to retrieve the contract negotiation.
     */
    public IdResponse doContractNegotiation(NegotiationRequest negotiationRequest) {
        try {
            this.checkEmptyVariables();
            LogUtil.printDebug("[" + negotiationRequest.getOffer().getOfferId() + "] ===== [INITIALIZING CONTRACT NEGOTIATION] ===========================================");
            String url = CatenaXUtil.buildManagementEndpoint(env, this.negotiationPath);
            HttpHeaders headers = httpUtil.getHeaders();
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            ResponseEntity<?> response = httpUtil.doPost(url, JsonNode.class, headers, httpUtil.getParams(), negotiationRequest, false, false);
            JsonNode result = (JsonNode) response.getBody();
            return (IdResponse) jsonUtil.bindJsonNode(result, IdResponse.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "doContractNegotiations",
                    e,
                    "It was not possible to retrieve the contract negotiation!");
        }
    }

    /**
     * Initiates the Contract Negotiation in order to retrieve it.
     * <p>
     * @param   contractOffer
     *          the {@code Offer} object with contract offer data.
     * @param   bpn
     *          the {@code String} BPN number from BNP discovery for the request.
     * @param   providerUrl
     *          the {@code String} URL from the provider.
     *
     * @return  a {@code IdResponse} object with the contract negotiation response.
     *
     * @throws  ServiceException
     *           if unable to retrieve the contract negotiation.
     */
    public IdResponse doContractNegotiation(Offer contractOffer, String bpn,  String providerUrl) {
        try {
            this.checkEmptyVariables();
            NegotiationRequest body = new NegotiationRequest(
                    jsonUtil.toJsonNode(Map.of("odrl", "http://www.w3.org/ns/odrl/2/")),
                    providerUrl,
                    bpn,
                    contractOffer
            );
            return this.doContractNegotiation(body);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "doContractNegotiations",
                    e,
                    "It was not possible to execute the contract negotiation!");
        }
    }

    /**
     * Gets the Negotiation data object from a Negotiation Response related to a Process.
     * <p>
     * @param   id
     *          the {@code String} id of the negotiation response.
     * @param   processId
     *          the {@code String} id of the application's process.
     * @param   dataModel
     *          the {@code ProcessDataModel} object of the process's data model.
     *
     * @return  a {@code Negotiation} object with the negotiation data.
     *
     * @throws  ServiceException
     *           if unable to see the negotiation.
     */
    public Negotiation seeNegotiation(String id, String processId, ProcessDataModel dataModel) {
        try {
            this.checkEmptyVariables();

            String endpoint = CatenaXUtil.buildManagementEndpoint(env, this.negotiationPath);
            // Get variables from configuration
            String url = endpoint + "/" + id;
            HttpHeaders headers = httpUtil.getHeaders();
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            Map<String, Object> params = httpUtil.getParams();
            JsonNode body = null;
            String actualState = "";
            boolean sw = true;
            Instant start = Instant.now();
            Instant end = start;
            LogUtil.printDebug("[" + id + "] ===== [STARTING CHECKING STATUS FOR CONTRACT NEGOTIATION]  ===========================================");
            while (sw) {
                ResponseEntity<?> response = httpUtil.doGet(url, JsonNode.class, headers, params, false, false);
                body = (JsonNode) response.getBody();
                if (body == null) {
                    sw = false;
                    throw new ServiceException(this.getClass().getName() + "." + "getNegotiations",
                            "No response received from url [" + url + "]!");
                }
                if (!body.has("edc:state") || body.get("edc:state") == null) {
                    LogUtil.printDebug("[" + id + "] ===== [ERROR CONTRACT NEGOTIATION] ===========================================");
                    throw new ServiceException(this.getClass().getName() + "." + "getNegotiations",
                            "It was not possible to do contract negotiations!");
                }
                String state = body.get("edc:state").asText();
                if (state.equals("CONFIRMED") || state.equals("ERROR") || state.equals("FINALIZED") || state.equals("TERMINATED") || state.equals("TERMINATING")) {
                    sw = false;
                    LogUtil.printDebug("[" + id + "] ===== [FINISHED CONTRACT NEGOTIATION] ===========================================");
                }
                if (!state.equals(actualState)) {
                    actualState = state; // Update current state
                    end = Instant.now();
                    Duration timeElapsed = Duration.between(start, end);
                    LogUtil.printDebug("[" + id + "] The contract negotiation status changed: [" + state + "] - TIME->[" + timeElapsed + "]s");
                    start = Instant.now();
                }
                if (dataModel.getState(processId).equals("TERMINATED")) {
                    LogUtil.printStatus("[" + id + "] The negotiation was cancelled");
                    return null;
                }
                if(sw){
                    ThreadUtil.sleep(this.env.getProperty("configuration.edc.delay", Integer.class, 200)); // Wait some milliseconds
                }
            }
            return (Negotiation) jsonUtil.bindJsonNode(body, Negotiation.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getNegotiation",
                    e,
                    "It was not possible to see the contract negotiation!");
        }
    }

    /**
     * Gets the Negotiation data object from a Negotiation Response.
     * <p>
     * @param   id
     *          the {@code String} id of the negotiation response.
     *
     * @return  a {@code Negotiation} object with the negotiation data.
     *
     * @throws  ServiceException
     *           if unable to see the negotiation.
     */
    public Negotiation seeNegotiation(String id) {
        try {
            this.checkEmptyVariables();

            String endpoint = CatenaXUtil.buildManagementEndpoint(env, this.negotiationPath);
            // Get variables from configuration
            String url = endpoint + "/" + id;
            HttpHeaders headers = httpUtil.getHeaders();
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            Map<String, Object> params = httpUtil.getParams();
            JsonNode body = null;
            String actualState = "";
            boolean sw = true;
            Instant start = Instant.now();
            Instant end = start;
            LogUtil.printDebug("[" + id + "] ===== [STARTING CHECKING STATUS FOR CONTRACT NEGOTIATION]  ===========================================");
            while (sw) {
                ResponseEntity<?> response = httpUtil.doGet(url, JsonNode.class, headers, params, false, false);
                body = (JsonNode) response.getBody();
                if (body == null) {
                    sw = false;
                    throw new ServiceException(this.getClass().getName() + "." + "getNegotiations",
                            "No response received from url [" + url + "]!");
                }
                if (!body.has("edc:state") || body.get("edc:state") == null) {
                    LogUtil.printDebug("[" + id + "] ===== [ERROR CONTRACT NEGOTIATION] ===========================================");
                    throw new ServiceException(this.getClass().getName() + "." + "getNegotiations",
                            "It was not possible to do contract negotiations!");
                }
                String state = body.get("edc:state").asText();
                if (state.equals("CONFIRMED") || state.equals("ERROR") || state.equals("FINALIZED") || state.equals("TERMINATED") || state.equals("TERMINATING")) {
                    sw = false;
                    LogUtil.printDebug("[" + id + "] ===== [FINISHED CONTRACT NEGOTIATION] ===========================================");
                }
                if (!state.equals(actualState)) {
                    actualState = state; // Update current state
                    end = Instant.now();
                    Duration timeElapsed = Duration.between(start, end);
                    LogUtil.printDebug("[" + id + "] The contract negotiation status changed: [" + state + "] - TIME->[" + timeElapsed + "]s");
                    start = Instant.now();
                }
                if(sw){
                    ThreadUtil.sleep(this.env.getProperty("configuration.edc.delay", Integer.class, 200)); // Wait half a second to not overflow the edc
                }
            }
            return (Negotiation) jsonUtil.bindJsonNode(body, Negotiation.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getNegotiation",
                    e,
                    "It was not possible to see the contract negotiation!");
        }
    }

    /**
     * Initiates a transfer regarding a transfer request.
     * <p>
     * @param   transferRequest
     *          the {@code TransferRequest} object with transfer request data.
     *
     * @return  a {@code IdResponse} object with the transfer response.
     *
     * @throws  ServiceException
     *           if unable to see the negotiation.
     */
    public IdResponse initiateTransfer(TransferRequest transferRequest) {
        try {
            this.checkEmptyVariables();
            HttpHeaders headers = httpUtil.getHeaders();
            // Get variables from configuration
            String url = CatenaXUtil.buildManagementEndpoint(env, this.transferPath);

            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            Object body = transferRequest;
            ResponseEntity<?> response = httpUtil.doPost(url, String.class, headers, httpUtil.getParams(), body, false, false);
            String responseBody = (String) response.getBody();
            return (IdResponse) jsonUtil.bindJsonNode(jsonUtil.toJsonNode(responseBody), IdResponse.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "doTransferProcess",
                    e,
                    "It was not possible to initiate transfer process!");
        }
    }

    /**
     * Gets the Transfer data object from a Transfer Response.
     * <p>
     * @param   id
     *          the {@code String} id of the transfer response.
     *
     * @return  a {@code Negotiation} object with the negotiation data.
     *
     * @throws  ServiceException
     *           if unable to get the transfer data.
     */
    public Transfer seeTransfer(String id) {
        try {
            this.checkEmptyVariables();
            HttpHeaders headers = httpUtil.getHeaders();
            String endpoint = CatenaXUtil.buildManagementEndpoint(env, this.transferPath);
            String path = endpoint + "/" + id;
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            Map<String, Object> params = httpUtil.getParams();
            JsonNode body = null;
            String actualState = "";
            boolean sw = true;
            Instant start = Instant.now();
            Instant end = start;
            LogUtil.printDebug("[" + id + "] ===== [STARTING CONTRACT TRANSFER] ===========================================");
            while (sw) {
                ResponseEntity<?> response = httpUtil.doGet(path, JsonNode.class, headers, params, false, false);
                body = (JsonNode) response.getBody();
                if (body == null) {
                    sw = false;
                    throw new ServiceException(this.getClass().getName() + "." + "getNegotiations",
                            "No response received from url [" + path + "]!");
                }
                if (!body.has("edc:state") || body.get("edc:state") == null) {
                    LogUtil.printDebug("[" + id + "] ===== [ERROR CONTRACT TRANSFER]===========================================");
                    throw new ServiceException(this.getClass().getName() + "." + "getTransfer",
                            "It was not possible to do the transfer process!");
                }
                String state = body.get("edc:state").asText();
                if (state.equals("COMPLETED") || state.equals("ERROR") || state.equals("FINALIZED") || state.equals("VERIFIED") || state.equals("TERMINATED") || state.equals("TERMINATING")) {
                    LogUtil.printDebug("[" + id + "] ===== [FINISHED CONTRACT TRANSFER] [" + id + "]===========================================");
                    sw = false;
                }
                if (!state.equals(actualState)) {
                    actualState = state; // Update current state
                    end = Instant.now();
                    Duration timeElapsed = Duration.between(start, end);
                    LogUtil.printDebug("[" + id + "] The data transfer status changed: [" + state + "] - TIME->[" + timeElapsed + "]s");
                    start = Instant.now();
                }
            }
            return (Transfer) jsonUtil.bindJsonNode(body, Transfer.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getTransfer",
                    e,
                    "It was not possible to transfer the contract! " + id);
        }
    }

    /**
     * Gets the Transfer data object from a Transfer Response related to a Process.
     * <p>
     * @param   id
     *          the {@code String} id of the transfer response.
     * @param   processId
     *          the {@code String} id of the application's process.
     * @param   dataModel
     *          the {@code ProcessDataModel} object of the process's data model.
     *
     * @return  a {@code Negotiation} object with the negotiation data.
     *
     * @throws  ServiceException
     *           if unable to get the transfer data.
     */
    public Transfer seeTransfer(String id, String processId, ProcessDataModel dataModel) {
        try {
            this.checkEmptyVariables();
            HttpHeaders headers = httpUtil.getHeaders();
            String endpoint = CatenaXUtil.buildManagementEndpoint(env, this.transferPath);
            String path = endpoint + "/" + id;
            headers.add("Content-Type", "application/json");
            headers.add("X-Api-Key", this.apiKey);
            Map<String, Object> params = httpUtil.getParams();
            JsonNode body = null;
            String actualState = "";
            boolean sw = true;
            Instant start = Instant.now();
            Instant end = start;
            LogUtil.printDebug("[" + id + "] ===== [STARTING CONTRACT TRANSFER] ===========================================");
            while (sw) {
                ResponseEntity<?> response = httpUtil.doGet(path, JsonNode.class, headers, params, false, false);
                body = (JsonNode) response.getBody();
                if (body == null) {
                    sw = false;
                    throw new ServiceException(this.getClass().getName() + "." + "getNegotiations",
                            "No response received from url [" + path + "]!");
                }
                if (!body.has("edc:state") || body.get("edc:state") == null) {
                    LogUtil.printDebug("[" + id + "] ===== [ERROR CONTRACT TRANSFER]===========================================");
                    throw new ServiceException(this.getClass().getName() + "." + "getTransfer",
                            "It was not possible to do the transfer process!");
                }
                String state = body.get("edc:state").asText();
                if (state.equals("COMPLETED") || state.equals("ERROR") || state.equals("FINALIZED") || state.equals("VERIFIED") || state.equals("TERMINATED") || state.equals("TERMINATING")) {
                    LogUtil.printDebug("[" + id + "] ===== [FINISHED CONTRACT TRANSFER] [" + id + "]===========================================");
                    sw = false;
                }
                if (!state.equals(actualState)) {
                    actualState = state; // Update current state
                    end = Instant.now();
                    Duration timeElapsed = Duration.between(start, end);
                    LogUtil.printDebug("[" + id + "] The data transfer status changed: [" + state + "] - TIME->[" + timeElapsed + "]s");
                    start = Instant.now();
                }
                if (dataModel.getState(processId).equals("TERMINATED")) {
                    LogUtil.printStatus("[" + id + "] The transfer was cancelled");
                    return null;
                }
            }
            return (Transfer) jsonUtil.bindJsonNode(body, Transfer.class);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getTransfer",
                    e,
                    "It was not possible to transfer the contract! " + id);
        }
    }

    /**
     * Gets the Passport version 3 from the Process.
     * <p>
     * @param   transferProcessId
     *          the {@code String} id of the target passport.
     * @param   endpoint
     *          the {@code String} endpoint URL of the target passport.
     *
     * @return  a {@code PassportV3} object with the passport data.
     *
     * @throws  ServiceException
     *           if unable to get the passport.
     */
    @SuppressWarnings("Unused")
    public JsonNode getPassport(String transferProcessId, String endpoint) {
        try {
            this.checkEmptyVariables();
            Map<String, Object> params = httpUtil.getParams();
            HttpHeaders headers = httpUtil.getHeaders();
            headers.add("Accept", "application/octet-stream");
            boolean retry = false;

            ResponseEntity<?> response = null;
            try {
                response = httpUtil.doGet(endpoint, String.class, headers, params, false, false);
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName() + ".getPassport", "It was not possible to get passport with id " + transferProcessId);
            }
            String responseBody = (String) response.getBody();
            return (JsonNode) jsonUtil.toJsonNode(responseBody);
        } catch (Exception e) {
            throw new ServiceException(this.getClass().getName() + "." + "getPassport",
                    e,
                    "It was not possible to retrieve the getPassport for transferProcessId [" + transferProcessId + "]!");
        }
    }

    /** STATIC METHODS **/

    /**
     * Gets the Passport version 3 from the Process.
     * <p>
     * @param   negotiation
     *          the {@code Negotiation} object for the request.
     * @param   connectorId
     *          the {@code String} identification of the connector.
     * @param   connectorAddress
     *          the {@code String} URL address the of the connector.
     *
     * @return  a {@code PassportV3} object with the passport data.
     *
     * @throws  ServiceException
     *           if unable to get the passport.
     */
    @SuppressWarnings("Unused")
    public static String generateTransferId(Negotiation negotiation, String connectorId, String connectorAddress) {
        return CrypUtil.sha256(DateTimeUtil.getDateTimeFormatted("yyyyMMddHHmmssSSS") + negotiation.getId() + connectorId + connectorAddress);
    }

    /** INNER CLASSES **/

    /**
     * This inner class consists exclusively of methods to operate on executing the Contract Negotiation  .
     *
     * <p> The methods defined here are intended to do every needed operation in order to be able to Negotiate the Contract.
     *
     */
    public class NegotiateContract implements Runnable {

        /** ATTRIBUTES **/
        private NegotiationRequest negotiationRequest;
        private ProcessDataModel dataModel;
        private Dataset dataset;
        private Negotiation negotiation;
        private Transfer transfer;
        private TransferRequest transferRequest;
        private IdResponse negotiationResponse;
        private IdResponse tranferResponse;
        private Integer negotiationAttempts;
        private Integer transferAttempts;
        private Status status;
        private String bpn;
        private String processId;

        /** CONSTRUCTOR(S) **/
        public NegotiateContract() {};
        public NegotiateContract(ProcessDataModel dataModel, String processId, String bpn,  Dataset dataset, Status status) {
            this.dataModel = dataModel;
            this.processId = processId;
            this.dataset = dataset;
            this.status = status;
            this.bpn = bpn;
            this.negotiationRequest = buildRequest(dataset, status, bpn);
        }

        /** GETTERS AND SETTERS **/
        @SuppressWarnings("Unused")
        public void setNegotiationRequest(NegotiationRequest negotiationRequest) {
            this.negotiationRequest = negotiationRequest;
        }

        public Dataset getDataset() {
            return dataset;
        }

        public void setDataset(Dataset dataset) {
            this.dataset = dataset;
        }

        public Negotiation getNegotiation() {
            return negotiation;
        }

        public void setNegotiation(Negotiation negotiation) {
            this.negotiation = negotiation;
        }

        @SuppressWarnings("Unused")
        public NegotiationRequest getNegotiationRequest() {
            return negotiationRequest;
        }

        @SuppressWarnings("Unused")
        public ProcessDataModel getDataModel() {
            return dataModel;
        }

        @SuppressWarnings("Unused")
        public void setDataModel(ProcessDataModel dataModel) {
            this.dataModel = dataModel;
        }

        @SuppressWarnings("Unused")
        public Integer getNegotiationAttempts() {
            return negotiationAttempts;
        }

        @SuppressWarnings("Unused")
        public void setNegotiationAttempts(Integer negotiationAttempts) {
            this.negotiationAttempts = negotiationAttempts;
        }

        @SuppressWarnings("Unused")
        public Integer getTransferAttempts() {
            return transferAttempts;
        }

        @SuppressWarnings("Unused")
        public void setTransferAttempts(Integer transferAttempts) {
            this.transferAttempts = transferAttempts;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public String getProcessId() {
            return processId;
        }

        public void setProcessId(String processId) {
            this.processId = processId;
        }

        public Transfer getTransfer() {
            return transfer;
        }

        public void setTransfer(Transfer transfer) {
            this.transfer = transfer;
        }

        @SuppressWarnings("Unused")
        public TransferRequest getTransferRequest() {
            return transferRequest;
        }

        @SuppressWarnings("Unused")
        public void setTransferRequest(TransferRequest transferRequest) {
            this.transferRequest = transferRequest;
        }

        @SuppressWarnings("Unused")
        public IdResponse getNegotiationResponse() {
            return negotiationResponse;
        }

        @SuppressWarnings("Unused")
        public void setNegotiationResponse(IdResponse negotiationResponse) {
            this.negotiationResponse = negotiationResponse;
        }

        @SuppressWarnings("Unused")
        public IdResponse getTranferResponse() {
            return tranferResponse;
        }

        @SuppressWarnings("Unused")
        public void setTranferResponse(IdResponse tranferResponse) {
            this.tranferResponse = tranferResponse;
        }

        /** METHODS **/

        /**
         * Builds a transfer request with the given data.
         * <p>
         * @param   dataset
         *          the {@code Dataset} data for the contract offer.
         * @param   status
         *          the {@code Status} object of the process.
         * @param   negotiation
         *          the {@code Negotiation} object for the request.
         * @param   bpn
         *          the {@code String} BPN number from BNP discovery for the request.
         *
         * @return  a {@code TransferRequest} object with the given data.
         *
         * @throws  ServiceException
         *           if unable to build the transfer request.
         */
        public TransferRequest buildTransferRequest(Dataset dataset, Status status, Negotiation negotiation, String bpn) {
            try {
                String receiverEndpoint = env.getProperty("configuration.edc.receiverEndpoint") + "/" + this.processId; // Send process Id to identification the session.
                TransferRequest.TransferType transferType = new TransferRequest.TransferType();

                transferType.setContentType("application/octet-stream");
                transferType.setIsFinite(true);


                TransferRequest.DataDestination dataDestination = new TransferRequest.DataDestination();
                dataDestination.setType("HttpProxy");

                TransferRequest.PrivateProperties privateProperties = new TransferRequest.PrivateProperties();
                privateProperties.setReceiverHttpEndpoint(receiverEndpoint);
                return new TransferRequest(
                        jsonUtil.toJsonNode(Map.of("odrl", "http://www.w3.org/ns/odrl/2/")),
                        dataset.getAssetId(),
                        status.getEndpoint(),
                        bpn,
                        negotiation.getContractAgreementId(),
                        dataDestination,
                        false,
                        privateProperties,
                        "dataspace-protocol-http",
                        transferType
                );
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to build the transfer request!");
            }
        }

        /**
         * This method is exclusively for the Negotiation Process.
         *
         * <p> It's a Thread level method from Runnable interface and does the Negotiation Request, gets the Negotiation Response and saves in the Process.
         * Also builds Transfer Request, and gets the data from the Transfer Response and save it in the Process.
         *
         * @throws  ServiceException
         *           if unable to do the negotiation and/or transferring the data.
         */
        @Override
        public void run() {
            // NEGOTIATION PROCESS
            try {
                processManager.saveNegotiationRequest(processId, negotiationRequest, new IdResponse(processId, null), false);
                this.negotiationResponse = this.requestNegotiation(this.negotiationRequest);
                processManager.saveNegotiationRequest(processId, negotiationRequest, negotiationResponse, false);
                this.negotiation = this.getNegotiationData(negotiationResponse);
                if (this.negotiation == null) {
                    return;
                }
                processManager.saveNegotiation(this.processId, this.negotiation, false);
                String state = this.negotiation.getState();
                if (!(state.equals("CONFIRMED") || state.equals("FINALIZED"))) {
                    throw new ServiceException(this.getClass().getName(), "Contract Negotiation Process Failed [" + this.negotiation.getId() + "]");
                }
            } catch (Exception e) {
                processManager.setStatus(this.processId, "negotiation-failed", new History(
                        this.processId,
                        "FAILED"
                ));
                this.dataModel.setState(processId, "FAILED");
                throw new ServiceException(this.getClass().getName(), e, "Failed to do the contract negotiation!");
            }

            if (this.dataModel.getState(processId).equals("TERMINATED")) {
                LogUtil.printMessage("Terminated process " + processId + "stopped transfer!");
                return;
            }
            ;
            this.dataModel.setState(processId, "NEGOTIATED");
            LogUtil.printStatus("[PROCESS " + this.processId + "] Negotiation Finished with status [" + negotiation.getState() + "]!");
            // TRANSFER PROCESS
            try {
                this.transferRequest = buildTransferRequest(this.dataset, this.status, this.negotiation, this.bpn);
                processManager.saveTransferRequest(this.processId, transferRequest, new IdResponse(processId, null), false);
                this.tranferResponse = this.requestTransfer(transferRequest);
                processManager.saveTransferRequest(this.processId, transferRequest, this.tranferResponse, false);
                this.transfer = this.getTransferData(this.tranferResponse);
                if (this.transfer == null) {
                    return;
                }
                processManager.saveTransfer(this.processId, transfer, false);
                if (!transfer.getState().equals("COMPLETED")) {
                    throw new ServiceException(this.getClass().getName(), "Transfer Process Failed [" + this.tranferResponse.getId() + "]");
                }
            } catch (Exception e) {
                processManager.setStatus(processId, "transfer-failed", new History(
                        processId,
                        "FAILED"
                ));
                this.dataModel.setState(processId, "FAILED");
                throw new ServiceException(this.getClass().getName(), e, "Failed to do the contract transfer");
            }
            this.dataModel.setState(processId, "COMPLETED");
            LogUtil.printStatus("[PROCESS " + this.processId + "] Negotiation and Transfer Completed!");
        }

        /**
         * Gets the Negotiation data from the Negotiation Response.
         * <p>
         * @param   negotiationResponse
         *          the {@code IdResponse} object with negotiation response.
         *
         * @return  a {@code Negotiation} object with the negotiation data.
         *
         * @throws  ServiceException
         *           if unable to get negotiation data.
         */
        public Negotiation getNegotiationData(IdResponse negotiationResponse) {
            Negotiation negotiation = null;
            try {
                negotiation = seeNegotiation(negotiationResponse.getId(), this.processId, this.dataModel);
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to get the negotiation [" + negotiationResponse.getId() + "]");
            }
            return negotiation;
        }

        /**
         * Starts the negotiation by requesting it.
         * <p>
         * @param   negotiationRequest
         *          the {@code NegotiationRequest} object with negotiation request data.
         *
         * @return  a {@code IdResponse} object with the negotiation response.
         *
         * @throws  ServiceException
         *           if unable to request the negotiation.
         */
        public IdResponse requestNegotiation(NegotiationRequest negotiationRequest) {
            IdResponse negotiationResponse = null;
            try {
                negotiationResponse = doContractNegotiation(negotiationRequest);
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to start the negotiation for offer [" + negotiationRequest.getOffer().getOfferId() + "]");
            }

            if (negotiationResponse.getId() == null) {
                throw new ServiceException(this.getClass().getName(), "The ID from the Offer is null [" + negotiationRequest.getOffer().getOfferId() + "]");
            }
            LogUtil.printMessage("[PROCESS " + this.processId + "] Negotiation Requested [" + negotiationResponse.getId() + "]");
            return negotiationResponse;
        }

        /**
         * Starts the transfer by requesting it.
         * <p>
         * @param   transferRequest
         *          the {@code TransferRequest} object with transfer request data.
         *
         * @return  a {@code IdResponse} object with the transfer response.
         *
         * @throws  ServiceException
         *           if unable to request the transfer.
         */
        public IdResponse requestTransfer(TransferRequest transferRequest) {
            IdResponse transferResponse = null;
            try {
                transferResponse = initiateTransfer(transferRequest);
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to start the transfer for contract  [" + transferRequest.getContractId() + "]");
            }
            if (transferResponse.getId() == null) {
                throw new ServiceException(this.getClass().getName(), "The ID from the transfer is null for contract  [" + transferRequest.getContractId() + "]");
            }
            LogUtil.printStatus("[PROCESS " + this.processId + "] Transfer Requested [" + transferResponse.getId() + "]");
            return transferResponse;
        }

        /**
         * Gets the Transfer data from the response.
         * <p>
         * @param   transferResponse
         *          the {@code IdResponse} object with transfer response data.
         *
         * @return  a {@code Transfer} object with the transfer data.
         *
         * @throws  ServiceException
         *           if unable to get the transfer data.
         */
        public Transfer getTransferData(IdResponse transferResponse) {
            /*[8]=========================================*/
            // Check for transfer updates and the status
            Transfer transfer = null;
            try {
                transfer = seeTransfer(transferResponse.getId(), this.processId, this.dataModel);
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to get the transfer data [" + transferResponse.getId() + "]");
            }
            return transfer;
        }
    }

    public class DigitalTwinRegistryTransfer implements Runnable{

        /** ATTRIBUTES **/
        Dtr dtr;
        TransferRequest dtrRequest;
        String processId;
        String endpointId;
        Search search;
        Status status;
        Transfer transfer;
        IdResponse transferResponse;

        /** CONSTRUCTOR(S) **/
        public DigitalTwinRegistryTransfer(String processId, String endpointId, Status status, Search search, Dtr dtr) {
            this.dtr = dtr;
            this.endpointId = endpointId;
            this.processId = processId;
            this.status = status;
            this.search = search;
        }

        /** METHODS **/

        /**
         * This method is exclusively for the Digital Twin Registry (DTR) transfer.
         *
         * <p> It's a Thread level method from Runnable interface and builds the Transfer Request, requests the Transfer for the DTR, gets the Transfer data
         *  and saves in the Process.
         *
         * @throws  ServiceException
         *           if unable to do the contract transfer for the DTR.
         */
        @Override
        public void run() {
            try {
                this.dtrRequest = this.buildTransferRequest(this.processId,this.dtr, this.endpointId);
                processManager.saveTransferRequest(this.processId, dtrRequest, new IdResponse(processId, null), true);
                this.transferResponse = this.requestTransfer(dtrRequest);
                processManager.saveTransferRequest(this.processId, dtrRequest, this.transferResponse, true);
                this.transfer = this.getTransferData(this.transferResponse);
                if (this.transfer == null) {
                    return;
                }
                if(this.transfer.getState().equals("TERMINATED")){
                    processManager.setStatus(processId, "dtr-"+this.endpointId+"-transfer-incomplete", new History(
                            endpointId,
                            "INCOMPLETE"
                    ));
                }

                processManager.saveTransfer(this.processId, transfer, true);
            } catch (Exception e) {
                processManager.setStatus(processId, "dtr-transfer-failed", new History(
                        processId,
                        "FAILED"
                ));

                throw new ServiceException(this.getClass().getName(), e, "Failed to do the contract transfer for Digital Twin Registry");
            }
        }

        /**
         * Builds a transfer request for a given DTR.
         * <p>
         * @param   processId
         *          the {@code String} identification of the Process.
         * @param   dtr
         *          the {@code DTR} object data of the Digital Twin Registry.
         * @param   endpointId
         *          the {@code String} identification of the receiver endpoint.
         *
         * @return  a {@code TransferRequest} object with transfer request data.
         *
         * @throws  ServiceException
         *           if unable to build the DTR's transfer request.
         */
        public TransferRequest buildTransferRequest(String processId, Dtr dtr, String endpointId) {
            try {
                // Build transfer request to make the Digital Twin Query
                String receiverEndpoint = env.getProperty("configuration.edc.receiverEndpoint") + "/" + processId +  "/" + endpointId;
                TransferRequest.TransferType transferType = new TransferRequest.TransferType();

                transferType.setContentType("application/octet-stream");
                transferType.setIsFinite(true);
                TransferRequest.DataDestination dataDestination = new TransferRequest.DataDestination();
                dataDestination.setType("HttpProxy");

                TransferRequest.PrivateProperties privateProperties = new TransferRequest.PrivateProperties();
                privateProperties.setReceiverHttpEndpoint(receiverEndpoint);
                return new TransferRequest(
                        jsonUtil.toJsonNode(Map.of("odrl", "http://www.w3.org/ns/odrl/2/")),
                        dtr.getAssetId(),
                        CatenaXUtil.buildDataEndpoint(dtr.getEndpoint()),
                        bpnNumber,
                        dtr.getContractId(),
                        dataDestination,
                        false,
                        privateProperties,
                        "dataspace-protocol-http",
                        transferType
                );
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to build the transfer request!");
            }
        }

        /**
         * Gets the Transfer response of a given DTR's Transfer request.
         * <p>
         * @param   transferRequest
         *          the {@code TransferRequest} object with transfer request data.
         *
         * @return  a {@code IdResponse} object with transfer response data.
         *
         * @throws  ServiceException
         *           if unable to transfer the DTR's transfer response.
         */
        public IdResponse requestTransfer(TransferRequest transferRequest) {
            IdResponse transferResponse = null;
            try {
                transferResponse = initiateTransfer(transferRequest);
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to start the transfer for contract  [" + transferRequest.getContractId() + "]");
            }
            if (transferResponse.getId() == null) {
                throw new ServiceException(this.getClass().getName(), "The ID from the transfer is null for contract  [" + transferRequest.getContractId() + "]");
            }
            LogUtil.printStatus("[PROCESS " + this.processId + "] Transfer Requested [" + transferResponse.getId() + "] for Digital Twin");
            return transferResponse;
        }

        /**
         * Gets the Transfer data from the response.
         * <p>
         * @param   transferResponse
         *          the {@code IdResponse} object with transfer response data.
         *
         * @return  a {@code Transfer} object with the transfer data.
         *
         * @throws  ServiceException
         *           if unable to get the transfer data.
         */
        public Transfer getTransferData(IdResponse transferResponse) {
            /*[8]=========================================*/
            // Check for transfer updates and the status
            Transfer transfer = null;
            try {
                transfer = seeTransfer(transferResponse.getId());
            } catch (Exception e) {
                throw new ServiceException(this.getClass().getName(), e, "Failed to get the transfer data [" + transferResponse.getId() + "]");
            }
            return transfer;
        }

    }

}