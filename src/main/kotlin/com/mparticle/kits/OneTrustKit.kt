package com.mparticle.kits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.mparticle.MParticle
import com.mparticle.consent.CCPAConsent
import com.mparticle.consent.ConsentState
import com.mparticle.consent.GDPRConsent
import com.mparticle.identity.MParticleUser
import com.mparticle.internal.Logger
import com.mparticle.kits.KitIntegration.IdentityListener
import com.onetrust.otpublishers.headless.Public.Keys.OTBroadcastServiceKeys
import com.onetrust.otpublishers.headless.Public.OTPublishersHeadlessSDK
import com.onetrust.otpublishers.headless.Public.OTVendorListMode
import org.json.JSONArray
import org.json.JSONException

class OneTrustKit : KitIntegration(), IdentityListener {

    internal enum class ConsentRegulation { GDPR, CCPA }

    internal class OneTrustConsent(val vendorType: String? = null, val purpose: String, val regulation: ConsentRegulation)

    private val consentUpdatedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            processOneTrustConsent()
        }
    }

    private val oneTrustSdk: OTPublishersHeadlessSDK by lazy { OTPublishersHeadlessSDK(context) }

    private var mpAllConsentMapping = mutableMapOf<String, OneTrustConsent>()

    companion object {
        private var initializedOnce = false

        private const val MobileConsentGroups = "mobileConsentGroups"
        private const val IabConsentGroups = "vendorIABConsentGroups"
        private const val GoogleConsentGroups = "vendorGoogleConsentGroups"
        private const val GeneralConsentGroups = "vendorGeneralConsentGroups"

        internal const val CCPAPurposeValue = "data_sale_opt_out"
    }

    override fun getName(): String {
        return "OneTrust"
    }

    override fun setOptOut(optedOut: Boolean): List<ReportingMessage> {
        return listOf()
    }

    public override fun onKitCreate(settings: Map<String, String>, context: Context): List<ReportingMessage> {
        processMappings(settings[MobileConsentGroups])
        processMappings(settings[IabConsentGroups], OTVendorListMode.IAB)
        processMappings(settings[GoogleConsentGroups], OTVendorListMode.GOOGLE)
        processMappings(settings[GeneralConsentGroups], OTVendorListMode.GENERAL)

        if (!initializedOnce) {
            initializedOnce = true
            processOneTrustConsent()
            context.registerReceiver(consentUpdatedReceiver, IntentFilter(OTBroadcastServiceKeys.OT_CONSENT_UPDATED))
        }
        return listOf()
    }

    override fun getInstance(): Any {
        return oneTrustSdk
    }

    internal fun processMappings(setting: String?, vendorType: String? = null) {
        if (!setting.isNullOrEmpty()) {
            try {
                val settingJSON = JSONArray(setting)

                for (i in 0 until settingJSON.length()) {
                    val p = settingJSON.getJSONObject(i)
                    val otPurposeCode = p.optString("value")
                    val mpPurposeCode = p.optString("map")
                    val mpRegulation = if (mpPurposeCode == CCPAPurposeValue) ConsentRegulation.CCPA else ConsentRegulation.GDPR

                    if (otPurposeCode.isNullOrEmpty()) {
                        Logger.warning("Consent mapping is missing OneTrust's side: $this")
                    } else if (mpPurposeCode.isNullOrEmpty()) {
                        Logger.warning("Consent mapping is missing mParticle's side: $this")
                    } else {
                        mpAllConsentMapping[otPurposeCode] = OneTrustConsent(vendorType, mpPurposeCode, mpRegulation)
                    }
                }

            } catch (jse: JSONException) {
                Logger.error(jse, "Could not parse consent mapping!")
            }
        }
    }

    internal fun processOneTrustConsent() {
        MParticle.getInstance()?.Identity()?.currentUser?.let { user ->
            mpAllConsentMapping.iterator().forEach { entry ->
                var status: Int = 0
                try {
                    if (!entry.value.vendorType.isNullOrEmpty()) {
                        status = oneTrustSdk.getVendorDetails(entry.value.vendorType!!, entry.key)!!.getInt("consent")
                    } else {
                        status = oneTrustSdk.getConsentStatusForGroupId(entry.key)
                    }
                } catch (e: Exception) {
                    Logger.error(e, "Could not fetch consent from OneTrust!")
                }

                // -1 = Consent Not Collected
                if (status > -1) {
                    // 0 = Consent Not Given
                    // 1 or 2 = Consent Given
                    val consentGiven: Boolean = status > 0
                    setConsentStateEvent(user, entry.value, consentGiven)
                }
            }
        } ?: run {
            Logger.warning("OneTrust consent could not be processed as MParticle's Current user is not set")
        }
    }

    internal fun setConsentStateEvent(user: MParticleUser, consentMapping: OneTrustConsent, consentGiven: Boolean) {
        val time = System.currentTimeMillis()

        val consentState = user.consentState.let { ConsentState.withConsentState(it) }
        when (consentMapping.regulation) {
            ConsentRegulation.GDPR -> {
                consentState.addGDPRConsentState(consentMapping.purpose, GDPRConsent.builder(consentGiven).timestamp(time).build())
            }

            ConsentRegulation.CCPA -> {
                consentState.setCCPAConsentState(CCPAConsent.builder(consentGiven).timestamp(time).build())
            }
        }
        user.setConsentState(consentState.build())
    }

    override fun onIdentifyCompleted(mParticleUser: MParticleUser?, identityApiRequest: FilteredIdentityApiRequest?) {
    }

    override fun onLoginCompleted(mParticleUser: MParticleUser?, identityApiRequest: FilteredIdentityApiRequest?) {
    }

    override fun onLogoutCompleted(mParticleUser: MParticleUser?, identityApiRequest: FilteredIdentityApiRequest?) {
    }

    override fun onModifyCompleted(mParticleUser: MParticleUser?, identityApiRequest: FilteredIdentityApiRequest?) {
    }

    override fun onUserIdentified(mParticleUser: MParticleUser?) {
        processOneTrustConsent()
    }
}