package ar.tod5.menu;

import net.minecraft.network.chat.Component;

/** Lo que agrega el mod al cartel de arriba de la cabeza de cada jugador: la linea de la vida (va arriba del nombre). */
public interface CartelEstado {
	Component tod5$vida();
	void tod5$vida(Component c);
}
