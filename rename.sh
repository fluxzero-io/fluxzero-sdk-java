#!/bin/bash

set -e

./mvnw rewrite:run

rm -rf fluxzero-bom/ sdk/
mv flux-capacitor-bom fluxzero-bom
mv java-client sdk

./mvnw clean
./mvnw install
