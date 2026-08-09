# Triplex model artifacts

Generated model binaries and runtime data are stored in the private Hugging
Face repository:

<https://huggingface.co/macmacmacmac/triplex-model-artifacts>

Authenticate with the Hugging Face CLI, then restore the files at their
project-relative paths from the Triplex repository root:

```sh
hf download macmacmacmac/triplex-model-artifacts --local-dir .
```

The downloaded files are ignored by Git and should not be committed to the
GitHub repository.
