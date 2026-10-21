# Tarjetas de otras apps

Cualquier app de la TV puede ofrecer una tarjeta en Inicio. AdLessTV pregunta una vez por cada tarjeta, con **Mantener oculta** como respuesta por defecto, y recuerda la decisión para ese paquete, esa actividad y esa clave de firma: si la app cambia de firma, se vuelve a preguntar. Personalizar → Tarjetas de otras apps permite cambiar de opinión. El tema puede desactivar la función con `"module_cards": false`.

## Declarar una tarjeta

```xml
<activity android:name=".HomeCard" android:exported="true" android:label="@string/card_title"
    android:banner="@drawable/card_banner">
    <intent-filter>
        <action android:name="com.adlesstv.launcher.action.CARD" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="com.adlesstv.card.caption" android:resource="@string/card_caption" />
</activity>
```

- Título: la etiqueta de la actividad. Texto secundario: `com.adlesstv.card.caption` (texto o recurso, máximo 80 caracteres), visible en la cabecera al enfocarla.
- Arte: el banner de la actividad o, si falta, su icono, como cualquier otra tarjeta.
- Al pulsarla se abre esa actividad con la acción `com.adlesstv.launcher.action.CARD`.

AdLessTV no recibe ni envía datos a la app: solo lee el manifiesto instalado, sin red.
