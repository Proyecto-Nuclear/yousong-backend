-- Dictionary: "Diccionario operativo y reproducción".
-- FKs are RESTRICT by default; the only controlled unlink is participacion.dispositivo_id.

CREATE TABLE operacion.establecimiento (
    id            uuid         NOT NULL,
    nombre        varchar(120) NOT NULL,
    qr_slug       varchar(80)  NOT NULL,
    zona_horaria  varchar(64)  NOT NULL DEFAULT 'America/Bogota',
    activo        boolean      NOT NULL DEFAULT true,
    creado_at     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_establecimiento PRIMARY KEY (id),
    CONSTRAINT uq_establecimiento_qr_slug UNIQUE (qr_slug),
    CONSTRAINT ck_establecimiento_nombre CHECK (char_length(btrim(nombre)) BETWEEN 1 AND 120),
    CONSTRAINT ck_establecimiento_qr_slug CHECK (qr_slug ~ '^[a-z0-9-]+$')
);

CREATE TABLE operacion.lista_defecto (
    id                 uuid         NOT NULL,
    establecimiento_id uuid         NOT NULL,
    nombre             varchar(100) NOT NULL,
    version            integer      NOT NULL,
    politica_rotacion  varchar(20)  NOT NULL DEFAULT 'SECUENCIAL',
    creado_at          timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_lista_defecto PRIMARY KEY (id),
    CONSTRAINT fk_lista_defecto_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT uq_lista_defecto_version UNIQUE (establecimiento_id, nombre, version),
    CONSTRAINT uq_lista_defecto_id_establecimiento UNIQUE (id, establecimiento_id),
    CONSTRAINT ck_lista_defecto_version CHECK (version > 0),
    CONSTRAINT ck_lista_defecto_rotacion CHECK (politica_rotacion IN ('SECUENCIAL', 'ALEATORIA'))
);

CREATE TABLE operacion.sesion (
    id                 uuid        NOT NULL,
    establecimiento_id uuid        NOT NULL,
    lista_id           uuid        NOT NULL,
    fecha_operativa    date        NOT NULL,
    estado             varchar(16) NOT NULL DEFAULT 'ABIERTA',
    abierta_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cerrada_at         timestamptz,
    version            bigint      NOT NULL DEFAULT 0,
    cola_version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_sesion PRIMARY KEY (id),
    CONSTRAINT fk_sesion_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT fk_sesion_lista FOREIGN KEY (lista_id, establecimiento_id)
        REFERENCES operacion.lista_defecto (id, establecimiento_id),
    CONSTRAINT uq_sesion_id_establecimiento UNIQUE (id, establecimiento_id),
    CONSTRAINT ck_sesion_estado CHECK (estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO', 'CERRADA')),
    CONSTRAINT ck_sesion_cierre_ordenado CHECK (cerrada_at IS NULL OR cerrada_at >= abierta_at),
    CONSTRAINT ck_sesion_cerrada_exige_fecha CHECK (estado <> 'CERRADA' OR cerrada_at IS NOT NULL),
    CONSTRAINT ck_sesion_version CHECK (version >= 0),
    CONSTRAINT ck_sesion_cola_version CHECK (cola_version >= 0)
);
-- One session in operation per establishment.
CREATE UNIQUE INDEX uq_sesion_en_operacion ON operacion.sesion (establecimiento_id)
    WHERE estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO');
CREATE INDEX ix_sesion_fecha ON operacion.sesion (establecimiento_id, fecha_operativa);

CREATE TABLE operacion.dispositivo (
    id               uuid        NOT NULL,
    canal            varchar(12) NOT NULL,
    version_acceso   integer     NOT NULL DEFAULT 1,
    creado_at        timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ultimo_acceso_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_at        timestamptz NOT NULL,
    revocado_at      timestamptz,
    CONSTRAINT pk_dispositivo PRIMARY KEY (id),
    CONSTRAINT ck_dispositivo_canal CHECK (canal IN ('WEB', 'ANDROID')),
    CONSTRAINT ck_dispositivo_version CHECK (version_acceso > 0)
);
CREATE INDEX ix_dispositivo_expira ON operacion.dispositivo (expira_at);

CREATE TABLE operacion.participacion (
    id                  uuid        NOT NULL,
    sesion_id           uuid        NOT NULL,
    dispositivo_id      uuid,
    seudonimo           varchar(32) NOT NULL,
    ingreso_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ultima_actividad_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_participacion PRIMARY KEY (id),
    CONSTRAINT fk_participacion_sesion FOREIGN KEY (sesion_id) REFERENCES operacion.sesion (id),
    -- Controlled unlink applied by the identity retention policy.
    CONSTRAINT fk_participacion_dispositivo FOREIGN KEY (dispositivo_id)
        REFERENCES operacion.dispositivo (id) ON DELETE SET NULL,
    CONSTRAINT uq_participacion_sesion_dispositivo UNIQUE (sesion_id, dispositivo_id),
    CONSTRAINT uq_participacion_id_sesion UNIQUE (id, sesion_id),
    CONSTRAINT uq_participacion_sesion_seudonimo UNIQUE (sesion_id, seudonimo)
);

CREATE TABLE operacion.cancion (
    id                   uuid          NOT NULL,
    youtube_video_id     varchar(64)   NOT NULL,
    titulo               varchar(300),
    artista_verificado   varchar(160),
    miniatura_url        varchar(2048),
    duracion_proveedor_s integer,
    duracion_operativa_s integer       NOT NULL DEFAULT 240,
    genero_propio        varchar(60),
    fuente_clasificacion varchar(200),
    disponibilidad       varchar(20)   NOT NULL DEFAULT 'DESCONOCIDA',
    validado_at          timestamptz,
    metadatos_expiran_at timestamptz,
    CONSTRAINT pk_cancion PRIMARY KEY (id),
    CONSTRAINT uq_cancion_youtube_video_id UNIQUE (youtube_video_id),
    CONSTRAINT ck_cancion_duracion_proveedor CHECK (duracion_proveedor_s IS NULL OR duracion_proveedor_s > 0),
    CONSTRAINT ck_cancion_duracion_operativa CHECK (duracion_operativa_s > 0),
    CONSTRAINT ck_cancion_disponibilidad CHECK (disponibilidad IN ('DESCONOCIDA', 'DISPONIBLE', 'NO_DISPONIBLE'))
);
CREATE INDEX ix_cancion_validado ON operacion.cancion (validado_at);

CREATE TABLE operacion.item_lista (
    id          uuid    NOT NULL,
    lista_id    uuid    NOT NULL,
    cancion_id  uuid    NOT NULL,
    posicion    integer NOT NULL,
    CONSTRAINT pk_item_lista PRIMARY KEY (id),
    CONSTRAINT fk_item_lista_lista FOREIGN KEY (lista_id) REFERENCES operacion.lista_defecto (id),
    CONSTRAINT fk_item_lista_cancion FOREIGN KEY (cancion_id) REFERENCES operacion.cancion (id),
    CONSTRAINT uq_item_lista_posicion UNIQUE (lista_id, posicion),
    CONSTRAINT uq_item_lista_id_lista_cancion UNIQUE (id, lista_id, cancion_id),
    CONSTRAINT ck_item_lista_posicion CHECK (posicion > 0)
);

CREATE TABLE operacion.solicitud (
    id            uuid        NOT NULL,
    sesion_id     uuid        NOT NULL,
    cancion_id    uuid        NOT NULL,
    creador_id    uuid        NOT NULL,
    estado        varchar(24) NOT NULL DEFAULT 'PENDIENTE',
    ciclo         integer     NOT NULL DEFAULT 1,
    creada_at     timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalizada_at timestamptz,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_solicitud PRIMARY KEY (id),
    CONSTRAINT fk_solicitud_sesion FOREIGN KEY (sesion_id) REFERENCES operacion.sesion (id),
    CONSTRAINT fk_solicitud_cancion FOREIGN KEY (cancion_id) REFERENCES operacion.cancion (id),
    CONSTRAINT fk_solicitud_creador FOREIGN KEY (creador_id, sesion_id)
        REFERENCES operacion.participacion (id, sesion_id),
    CONSTRAINT uq_solicitud_id_sesion_cancion UNIQUE (id, sesion_id, cancion_id),
    CONSTRAINT ck_solicitud_estado CHECK (estado IN ('PENDIENTE', 'RESERVADA', 'REPRODUCIENDO', 'ATENDIDA',
        'SALTADA', 'FALLIDA', 'BLOQUEADA', 'NO_ATENDIDA_CIERRE')),
    CONSTRAINT ck_solicitud_ciclo CHECK (ciclo > 0),
    CONSTRAINT ck_solicitud_version CHECK (version >= 0)
);
-- One active request per song and session.
CREATE UNIQUE INDEX uq_solicitud_activa ON operacion.solicitud (sesion_id, cancion_id)
    WHERE estado IN ('PENDIENTE', 'RESERVADA', 'REPRODUCIENDO');
CREATE INDEX ix_solicitud_cola ON operacion.solicitud (sesion_id, estado, creada_at, id);

CREATE TABLE operacion.voto (
    id               uuid        NOT NULL,
    solicitud_id     uuid        NOT NULL,
    participacion_id uuid        NOT NULL,
    sesion_id        uuid        NOT NULL,
    cancion_id       uuid        NOT NULL,
    es_inicial       boolean     NOT NULL DEFAULT false,
    creado_at        timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_voto PRIMARY KEY (id),
    CONSTRAINT fk_voto_solicitud FOREIGN KEY (solicitud_id, sesion_id, cancion_id)
        REFERENCES operacion.solicitud (id, sesion_id, cancion_id),
    CONSTRAINT fk_voto_participacion FOREIGN KEY (participacion_id, sesion_id)
        REFERENCES operacion.participacion (id, sesion_id),
    CONSTRAINT fk_voto_sesion FOREIGN KEY (sesion_id) REFERENCES operacion.sesion (id),
    CONSTRAINT fk_voto_cancion FOREIGN KEY (cancion_id) REFERENCES operacion.cancion (id),
    -- One support per participation and song during the session (no vote change in the PMV).
    CONSTRAINT uq_voto_participacion_cancion UNIQUE (participacion_id, sesion_id, cancion_id)
);
CREATE INDEX ix_voto_solicitud ON operacion.voto (solicitud_id);

CREATE TABLE operacion.estacion (
    id                 uuid        NOT NULL,
    establecimiento_id uuid        NOT NULL,
    sesion_id          uuid,
    nombre             varchar(80) NOT NULL,
    habilitada         boolean     NOT NULL DEFAULT false,
    fencing_token      bigint      NOT NULL DEFAULT 0,
    lease_hasta        timestamptz,
    heartbeat_at       timestamptz,
    CONSTRAINT pk_estacion PRIMARY KEY (id),
    CONSTRAINT fk_estacion_establecimiento FOREIGN KEY (establecimiento_id)
        REFERENCES operacion.establecimiento (id),
    CONSTRAINT fk_estacion_sesion FOREIGN KEY (sesion_id, establecimiento_id)
        REFERENCES operacion.sesion (id, establecimiento_id),
    CONSTRAINT uq_estacion_id_establecimiento UNIQUE (id, establecimiento_id),
    CONSTRAINT ck_estacion_fencing CHECK (fencing_token >= 0)
);
-- At most one enabled station per session.
CREATE UNIQUE INDEX uq_estacion_habilitada_sesion ON operacion.estacion (sesion_id) WHERE habilitada;

CREATE TABLE operacion.reproduccion (
    id            uuid        NOT NULL,
    sesion_id     uuid        NOT NULL,
    cancion_id    uuid        NOT NULL,
    estacion_id   uuid        NOT NULL,
    origen        varchar(12) NOT NULL,
    solicitud_id  uuid,
    item_lista_id uuid,
    fencing_token bigint      NOT NULL DEFAULT 0,
    preparada_at  timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    iniciada_at   timestamptz,
    terminada_at  timestamptz,
    resultado     varchar(24),
    motivo_codigo varchar(60),
    CONSTRAINT pk_reproduccion PRIMARY KEY (id),
    CONSTRAINT fk_reproduccion_sesion FOREIGN KEY (sesion_id) REFERENCES operacion.sesion (id),
    CONSTRAINT fk_reproduccion_cancion FOREIGN KEY (cancion_id) REFERENCES operacion.cancion (id),
    CONSTRAINT fk_reproduccion_estacion FOREIGN KEY (estacion_id) REFERENCES operacion.estacion (id),
    CONSTRAINT fk_reproduccion_solicitud FOREIGN KEY (solicitud_id, sesion_id, cancion_id)
        REFERENCES operacion.solicitud (id, sesion_id, cancion_id),
    CONSTRAINT fk_reproduccion_item_lista FOREIGN KEY (item_lista_id) REFERENCES operacion.item_lista (id),
    CONSTRAINT ck_reproduccion_origen CHECK (origen IN ('SOLICITUD', 'DEFECTO')),
    -- Exclusive origin: a request or a default-list item.
    CONSTRAINT ck_reproduccion_origen_exclusivo CHECK (
        (origen = 'SOLICITUD' AND solicitud_id IS NOT NULL AND item_lista_id IS NULL)
        OR (origen = 'DEFECTO' AND item_lista_id IS NOT NULL AND solicitud_id IS NULL)),
    CONSTRAINT ck_reproduccion_resultado CHECK (resultado IS NULL
        OR resultado IN ('COMPLETADA', 'SALTADA', 'FALLIDA', 'INTERRUMPIDA_CIERRE')),
    CONSTRAINT ck_reproduccion_fechas CHECK (
        (iniciada_at IS NULL OR iniciada_at >= preparada_at)
        AND (terminada_at IS NULL OR terminada_at >= preparada_at)
        AND (iniciada_at IS NULL OR terminada_at IS NULL OR terminada_at >= iniciada_at)),
    CONSTRAINT ck_reproduccion_resultado_con_cierre CHECK ((resultado IS NULL) = (terminada_at IS NULL)),
    CONSTRAINT ck_reproduccion_completada_inicio CHECK (resultado <> 'COMPLETADA' OR iniciada_at IS NOT NULL),
    CONSTRAINT ck_reproduccion_fencing CHECK (fencing_token >= 0)
);
-- A single open playback attempt per session.
CREATE UNIQUE INDEX uq_reproduccion_activa ON operacion.reproduccion (sesion_id) WHERE terminada_at IS NULL;
CREATE INDEX ix_reproduccion_inicio ON operacion.reproduccion (sesion_id, iniciada_at);
CREATE INDEX ix_reproduccion_solicitud ON operacion.reproduccion (solicitud_id);
