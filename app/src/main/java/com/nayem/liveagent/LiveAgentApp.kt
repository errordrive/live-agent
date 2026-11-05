package com.nayem.liveagent

import android.app.Application
import com.nayem.liveagent.agent.AgentOrchestrator
import com.nayem.liveagent.data.AppStores
import com.nayem.liveagent.data.SecureStore

class LiveAgentApp : Application() {

    companion object {
        lateinit var stores: AppStores
        lateinit var secure: SecureStore
        lateinit var orchestrator: AgentOrchestrator
    }

    override fun onCreate() {
        super.onCreate()
        stores = AppStores(this)
        secure = SecureStore(this)
        orchestrator = AgentOrchestrator(this, stores, secure)
    }
}
