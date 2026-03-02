#!/bin/bash
echo "Verifying ModelDownloadManager.kt"
grep -n "HF token" app/src/main/java/com/tronprotocol/app/llm/ModelDownloadManager.kt
echo "Verifying EthicalKernelVerifier.kt"
grep -n "Partner update rejected" app/src/main/java/com/tronprotocol/app/security/EthicalKernelVerifier.kt
