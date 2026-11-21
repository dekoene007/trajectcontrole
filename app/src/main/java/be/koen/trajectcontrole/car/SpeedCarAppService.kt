package be.koen.trajectcontrole.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Ingang voor Android Auto. Eigen gebruik -> alle hosts toelaten (Android Auto 'Onbekende bronnen'). */
class SpeedCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        // kaartscherm vanaf Car API 7 (MapWithContentTemplate), anders het eenvoudige paneel
        override fun onCreateScreen(intent: Intent): Screen =
            if (carContext.carAppApiLevel >= 7) CarMapScreen(carContext) else SpeedScreen(carContext)
    }
}
