/*********************************************************************************
 *
 * Catena-X - Product Passport Consumer Backend
 *
 * Copyright (c) 2022, 2023 BASF SE, BMW AG, Henkel AG & Co. KGaA
 * Copyright (c) 2022, 2023 Contributors to the CatenaX (ng) GitHub Organisation.
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

package utils;

import org.eclipse.tractusx.digitalproductpass.models.edc.DataPlaneEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import utils.exceptions.UtilException;

/**
 * This class consists exclusively of methods to operate on Eclipse Dataspace Connector (EDC) data.
 *
 * <p> The methods defined here are intended to check or manipulate EDC's related data.
 *
 */
@Component
public class EdcUtil {

    private final JsonUtil jsonUtil;
    @Autowired
    public EdcUtil(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    /**
     * Parses the data from HTTP request {@code Object} body to a {@code DataPLaneEndpoint} object data.
     * <p>
     * @param   body
     *          the {@code Object} object representing the body for the request.
     *
     * @return  a {@code DataPlaneEndpoint} object with the parsed date retrieved from the body.
     *
     * @throws  UtilException
     *          if unable to parse to the data.
     */
    public DataPlaneEndpoint parseDataPlaneEndpoint(Object body){
        try {
            return (DataPlaneEndpoint) this.jsonUtil.bindObject(body, DataPlaneEndpoint.class);
        }catch (Exception e){
            throw new UtilException(EdcUtil.class, e, "It was not possible to parse the data plain endpoint");
        }
    }

    // This method is responsible for finding if the EDC is version v0.5.0 basing itself in the contractId format.
    /**
     * Checks if the EDC is version v0.5.0 basing on the contractId format.
     * <p>
     * @param   contractId
     *          the contractId of the Digital Twin Registry (DTR) as a String.
     *
     * @return  true if the EDC's version is v0.5.0, false otherwise.
     *
     * @throws  UtilException
     *          if unable to check the EDC's version.
     */
    @SuppressWarnings("Unused")
    public Boolean isEdc5(String contractId){
        try {
            String[] parts = contractId.split(String.format("\\%s",":"));
            if(parts.length != 3){
                throw new UtilException(EdcUtil.class, "It was not possible to check if EDC is v0.5.0, Invalid Contract Id");
            }
            return CrypUtil.isBase64(parts[1]); // If the contractId is base64 encoded
        }catch (Exception e){
            throw new UtilException(EdcUtil.class, e, "It was not possible check if is the EDC v0.5.0");
        }
    }

}