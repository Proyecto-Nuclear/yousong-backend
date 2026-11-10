-- Dictionary: "Diccionario de acceso control y privacidad".
-- These tables are never published in the public queue or the general dashboard.

CREATE TABLE operacion.personal (
    id            uuid         NOT NULL,
    usuario       varchar(80)  NOT NULL,
    nombre        varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    activo        boolean      NOT NULL DEFAULT true,
    creado_at     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_personal PRIMARY KEY (id),
    CONSTRAINT uq_personal_usuario UNIQUE (usuario),
    CONSTRAINT ck_personal_usuario CHECK (usuario = lower(btrim(usuario)) AND usuario !~ '\s')
);

CREATE TABLE operacion.membresia (
    id                 uuid        NOT NULL,
    personal_id        uuid        NOT NULL,
    establecimiento_id uuid        NOT NULL,
    rol                varchar(20) NOT NULL,
    activa             boolean     NOT NULL DEFAULT true,
    CONSTRAINT pk_membresia PRIMARY KEY (id),
    CONSTRAINT fk_membresia_personal FOREIGN KEY (personal_id) REFERENCES operacion.personal (id),
    CONSTRAINT fk_membresia_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT uq_membresia_personal_establecimiento UNIQUE (personal_id, establecimiento_id),
    CONSTRAINT ck_membresia_rol CHECK (rol IN ('ADMINISTRADOR', 'OPERADOR'))
);

CREATE TABLE operacion.sesion_acceso (
    id             uuid        NOT NULL,
    personal_id    uuid,
    dispositivo_id uuid,
    familia_id     uuid        NOT NULL,
    refresh_hash   varchar(64) NOT NULL,
    emitida_at     timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_at      timestamptz NOT NULL,
    revocada_at    timestamptz,
    CONSTRAINT pk_sesion_acceso PRIMARY KEY (id),
    CONSTRAINT fk_sesion_acceso_personal FOREIGN KEY (personal_id) REFERENCES operacion.personal (id),
    CONSTRAINT fk_sesion_acceso_dispositivo FOREIGN KEY (dispositivo_id) REFERENCES operacion.dispositivo (id),
    CONSTRAINT uq_sesion_acceso_refresh_hash UNIQUE (refresh_hash),
    -- Exactly one owner: staff account or participant device.
    CONSTRAINT ck_sesion_acceso_propietario CHECK ((personal_id IS NULL) <> (dispositivo_id IS NULL)),
    CONSTRAINT ck_sesion_acceso_hash CHECK (refresh_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_sesion_acceso_expira ON operacion.sesion_acceso (expira_at);
CREATE INDEX ix_sesion_acceso_familia ON operacion.sesion_acceso (familia_id);

CREATE TABLE operacion.consentimiento (
    id               uuid        NOT NULL,
    participacion_id uuid        NOT NULL,
    politica_version varchar(20) NOT NULL,
    finalidad        varchar(4)  NOT NULL,
    accion           varchar(12) NOT NULL,
    canal            varchar(12) NOT NULL,
    registrado_at    timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_consentimiento PRIMARY KEY (id),
    CONSTRAINT fk_consentimiento_participacion FOREIGN KEY (participacion_id)
        REFERENCES operacion.participacion (id),
    CONSTRAINT ck_consentimiento_finalidad CHECK (finalidad IN ('F1', 'F2')),
    CONSTRAINT ck_consentimiento_accion CHECK (accion IN ('AUTORIZA', 'REVOCA')),
    CONSTRAINT ck_consentimiento_canal CHECK (canal IN ('WEB', 'ANDROID'))
);
CREATE INDEX ix_consentimiento_participacion ON operacion.consentimiento (participacion_id, finalidad, registrado_at);

CREATE TABLE operacion.horario (
    id                 uuid        NOT NULL,
    establecimiento_id uuid        NOT NULL,
    dia_semana         smallint    NOT NULL,
    inicio             time        NOT NULL,
    fin                time        NOT NULL,
    cruza_medianoche   boolean     NOT NULL DEFAULT false,
    activo             boolean     NOT NULL DEFAULT true,
    CONSTRAINT pk_horario PRIMARY KEY (id),
    CONSTRAINT fk_horario_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT ck_horario_dia CHECK (dia_semana BETWEEN 1 AND 7)
);
CREATE INDEX ix_horario_dia ON operacion.horario (establecimiento_id, dia_semana);

CREATE TABLE operacion.bloqueo_contenido (
    id                 uuid        NOT NULL,
    establecimiento_id uuid        NOT NULL,
    cancion_id         uuid        NOT NULL,
    personal_id        uuid        NOT NULL,
    desde_at           timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hasta_at           timestamptz,
    levantado_at       timestamptz,
    motivo_codigo      varchar(40) NOT NULL,
    CONSTRAINT pk_bloqueo_contenido PRIMARY KEY (id),
    CONSTRAINT fk_bloqueo_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT fk_bloqueo_cancion FOREIGN KEY (cancion_id) REFERENCES operacion.cancion (id),
    CONSTRAINT fk_bloqueo_personal FOREIGN KEY (personal_id) REFERENCES operacion.personal (id),
    CONSTRAINT ck_bloqueo_fechas CHECK (
        (hasta_at IS NULL OR hasta_at >= desde_at) AND (levantado_at IS NULL OR levantado_at >= desde_at))
);
CREATE INDEX ix_bloqueo_vigencia ON operacion.bloqueo_contenido (establecimiento_id, cancion_id, desde_at);

CREATE TABLE operacion.comando (
    id            uuid         NOT NULL,
    ambito        varchar(160) NOT NULL,
    clave         uuid         NOT NULL,
    peticion_hash varchar(64)  NOT NULL,
    estado        varchar(16)  NOT NULL DEFAULT 'EN_PROCESO',
    respuesta     jsonb,
    creado_at     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_at     timestamptz  NOT NULL,
    CONSTRAINT pk_comando PRIMARY KEY (id),
    CONSTRAINT uq_comando_ambito_clave UNIQUE (ambito, clave),
    CONSTRAINT ck_comando_estado CHECK (estado IN ('EN_PROCESO', 'COMPLETADO')),
    CONSTRAINT ck_comando_hash CHECK (peticion_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_comando_expira ON operacion.comando (expira_at);

CREATE TABLE operacion.auditoria (
    id                 uuid        NOT NULL,
    establecimiento_id uuid,
    personal_id        uuid,
    accion_codigo      varchar(60) NOT NULL,
    entidad_tipo       varchar(50) NOT NULL,
    entidad_ref        uuid,
    resultado          varchar(12) NOT NULL,
    correlacion_id     uuid        NOT NULL,
    registrada_at      timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_auditoria PRIMARY KEY (id),
    CONSTRAINT fk_auditoria_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT fk_auditoria_personal FOREIGN KEY (personal_id) REFERENCES operacion.personal (id),
    CONSTRAINT ck_auditoria_resultado CHECK (resultado IN ('EXITO', 'RECHAZO', 'ERROR'))
);
CREATE INDEX ix_auditoria_establecimiento ON operacion.auditoria (establecimiento_id, registrada_at);

CREATE TABLE operacion.solicitud_derecho (
    id               uuid         NOT NULL,
    participacion_id uuid,
    tipo             varchar(16)  NOT NULL,
    estado           varchar(16)  NOT NULL DEFAULT 'RECIBIDA',
    expediente_ref   varchar(120) NOT NULL,
    recibida_at      timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    vence_at         timestamptz  NOT NULL,
    cerrada_at       timestamptz,
    CONSTRAINT pk_solicitud_derecho PRIMARY KEY (id),
    CONSTRAINT fk_solicitud_derecho_participacion FOREIGN KEY (participacion_id)
        REFERENCES operacion.participacion (id),
    CONSTRAINT ck_solicitud_derecho_tipo CHECK (tipo IN ('CONSULTA', 'RECLAMO', 'REVOCATORIA', 'SUPRESION')),
    CONSTRAINT ck_solicitud_derecho_estado CHECK (estado IN ('RECIBIDA', 'EN_TRAMITE', 'RESPONDIDA', 'CERRADA'))
);
CREATE INDEX ix_solicitud_derecho_vencimiento ON operacion.solicitud_derecho (estado, vence_at);
