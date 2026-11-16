package ar.tod5.menu;

/** Lo que se agrega al "estado de dibujo" de cada entidad: para donde tiene la gravedad (para dibujarlo girado si es de costado). */
public interface GravedadEstado {
	GravedadCliente.G tod5$gravedad();
	void tod5$gravedad(GravedadCliente.G g);
}
