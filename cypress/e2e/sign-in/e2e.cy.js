/**
 * Copyright 2023 BASF SE, BMW AG, Henkel AG & Co. KGaA
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* eslint-disable no-undef */
describe("e2e test", () => {
  before(() => {
    cy.visit("http://localhost:8080");
  });
  it("Sign in and battery select", () => {
    cy.get('#username').type("company 2 user"); // typing username
    cy.get('#password').type("changeme"); // typing password
    cy.get('#kc-login').click(); // btn click
    cy.get('[data-cy="QR-scanner-tab"]').click(); // changing tab
    cy.get('[data-cy="qr-container"]'); // checking qr scanner display
    cy.get('[data-cy="history-tab"]').click(); // changing tab
    cy.get('[data-cy="history-container"]'); // checking history page display
    cy.get('[data-cy="passport-link"]').click(); // getting a passport 
    cy.wait(20000); // timeout for loading
    cy.get('[data-cy="battery-id"]'); // 1. General information first field check
    cy.get('[data-cy="electrolyte-composition"]'); // 2. Battery composition first field check
    cy.get('[data-cy="remaining-capacity"]'); // 3. State of health first field check
    cy.get('[data-cy="state-of-charge"]'); // 4. Parameters of the battery first field check
    cy.get('[data-cy="link-to-the-label-element"]'); // 8. Additional information first field check
    cy.get('[data-cy="footer"]'); //  Footer check
  });
});
