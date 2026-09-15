# The load generator for the rollout test, built from this repository and loaded into the cluster.
#
# Built rather than pulled on purpose. kind loads an image by exporting it with every platform it
# claims, and an image pulled on Docker Desktop is a multi-platform index with only one platform's
# layers present — so loading python:3.13-alpine straight in fails with "content digest not found".
# A local build is a single-platform image, which loads the way the platform's own images do.
FROM python:3.13-alpine
COPY load.py /load/load.py
ENTRYPOINT ["python", "/load/load.py"]
