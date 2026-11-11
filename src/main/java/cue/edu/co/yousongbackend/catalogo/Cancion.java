package cue.edu.co.yousongbackend.catalogo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps operacion.cancion. */
@Entity
@Table(schema = "operacion", name = "cancion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Cancion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "youtube_video_id", nullable = false, length = 64)
    private String youtubeVideoId;

    @Column(name = "titulo", length = 300)
    private String titulo;

    @Column(name = "artista_verificado", length = 160)
    private String artistaVerificado;

    @Column(name = "canal_titulo", length = 200)
    private String canalTitulo;

    @Column(name = "miniatura_url", length = 2048)
    private String miniaturaUrl;

    @Column(name = "duracion_proveedor_s")
    private Integer duracionProveedorS;

    @Column(name = "duracion_operativa_s", nullable = false)
    private int duracionOperativaS = 240;

    @Column(name = "genero_propio", length = 60)
    private String generoPropio;

    @Column(name = "fuente_clasificacion", length = 200)
    private String fuenteClasificacion;

    @Enumerated(EnumType.STRING)
    @Column(name = "disponibilidad", nullable = false, length = 20)
    private Disponibilidad disponibilidad = Disponibilidad.DESCONOCIDA;

    @Column(name = "validado_at")
    private Instant validadoAt;

    @Column(name = "metadatos_expiran_at")
    private Instant metadatosExpiranAt;
}
