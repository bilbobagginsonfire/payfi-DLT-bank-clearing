# Security Policy

## Project status

PayFi DLT Bank Clearing is a **pilot / proof of concept**. It runs on a dev-mode Corda 5.2 network with default credentials, R3 development certificates and synthetic demo data. It is **not** intended for production use or for processing real customer or payment data.

## Supported versions

Only the `main` branch gets fixes. There are no released versions.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Report vulnerabilities privately through GitHub's [private vulnerability reporting](https://github.com/bilbobagginsonfire/payfi-DLT-bank-clearing/security/advisories/new) for this repository. Please include:

- a description of the issue and its potential impact
- steps to reproduce, or a proof of concept
- affected files, flows or configuration

We aim to acknowledge reports within 5 working days and will keep you informed while we investigate and fix the issue.

## Scope

In scope: the CorDapp code (`contracts/`, `workflows/`), the frontend (`frontend/`) and the deployment guidance in `docs/`.

Known pilot limitations are out of scope unless they lead to a concrete exploit beyond what is documented. These include the default `admin` REST credentials for local development, R3 development certificates in `config/`, and the static dev-mode network.
