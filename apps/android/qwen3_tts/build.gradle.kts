plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("qwen3_tts")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
