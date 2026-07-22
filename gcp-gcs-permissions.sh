#!/bin/bash
PROJECT="project-0708fb26-e204-4400-ae9"
PROJECT_NUMBER="276371417285"
BUCKET="mybill-product-images"

# Cloud Run uses the Compute Engine default service account
CLOUD_RUN_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

# Grant Storage Object Admin on the bucket
gcloud storage buckets add-iam-policy-binding gs://$BUCKET --member="serviceAccount:$CLOUD_RUN_SA" --role="roles/storage.objectAdmin" --project=$PROJECT

echo "Done! Cloud Run can now write to gs://$BUCKET"
