package net.trollyloki.dicsit.interactions.cache;

import net.dv8tion.jda.api.entities.User;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

@NullMarked
public class ModalOptionCache<T> {

    private final InteractionDataCache<Long, List<T>> valuesForUser = new InteractionDataCache<>();

    public void put(User user, List<T> attachments) {
        valuesForUser.put(user.getIdLong(), attachments);
    }

    public @Nullable T pop(User user, int index) {
        List<T> values = valuesForUser.pop(user.getIdLong());
        if (values == null) return null;

        return values.get(index);
    }

}
