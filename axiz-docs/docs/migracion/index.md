# Migración entre versiones

Cada versión del framework publica sus **recetas de OpenRewrite** (`pe.axiz:axiz-rewrite-recipes`,
misma versión) y una guía en esta sección.

## Cómo actualizar un servicio

1. Renovate abre un PR que solo cambia el parent (`axiz-parent-service`) con la nota de la versión.
2. En ese branch:

    ```bash
    axiz upgrade --to 1.3.0 --dry-run   # muestra el diff de las recetas sin cambiar nada
    axiz upgrade --to 1.3.0             # parent, manifiesto y recetas
    ./mvnw verify
    ```

3. Revisa el diff, ajusta lo que la guía de la versión marque como manual y sube el PR.

`axiz upgrade` actualiza la versión en el `pom.xml` y en `.axiz/project.yaml` y ejecuta
`rewrite:run` con las recetas de la versión destino (`--skip-rewrite` solo cambia versiones). Las
recetas encadenan las de versiones intermedias, así se puede saltar de minor en minor.

## Política

- Se soportan la versión actual y la minor anterior.
- Lo obsoleto se marca `@Deprecated(forRemoval = true)` al menos una minor antes de borrarse, y la
  receta de esa versión hace el reemplazo.

## Guías por versión

### 1.0

Primera versión publicada: no hay migración. Un servicio creado con un prototipo anterior a la
1.0 se regenera con `axiz new` sobre su contrato y se le mueve el código de negocio.
