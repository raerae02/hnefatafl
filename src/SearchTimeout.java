// Levee quand le temps de reflexion est ecoule au milieu d'une recherche.
// L'approfondissement iteratif l'attrape et garde le coup de la derniere
// profondeur completee.
class SearchTimeout extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
