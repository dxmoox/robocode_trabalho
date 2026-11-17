package sample;

import robocode.*;
import robocode.util.Utils;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ANJOS extends TeamRobot {

    private static final double DIST_PREFERIDA = 380;
    private static final double MARGEM_ALIADO  = 50;
    private static final long   IDADE_MAX      = 40;
    private static final long   IDADE_ALIADO   = 15;

    private static class Alvo {
        String nome;
        double x, y, energia, rumo, velocidade;
        long tempo;
    }

    private static class Aliado {
        double x, y;
        long tempo;
    }

    private final Map<String, Alvo> inimigos = new HashMap<String, Alvo>();
    private final Map<String, Aliado> aliados = new HashMap<String, Aliado>();
    private final Set<String> aliadosVivos = new HashSet<String>();
    private final Set<String> mortos = new HashSet<String>();
    private final StringBuilder scansDoTurno = new StringBuilder();

    private String alvoAtual = null;
    private String alvoDoAliado = null;
    private int orbita = 1;
    private long proximaTroca = 0;
    private long ultimoAcerto = -100;
    private Rectangle2D.Double campo;

    @Override
    public void run() {

        campo = new Rectangle2D.Double(30, 30,
                getBattleFieldWidth() - 60, getBattleFieldHeight() - 60);

        String[] equipe = getTeammates();
        if (equipe != null) {
            for (String n : equipe) {
                aliadosVivos.add(n);
            }
        }

        setAdjustRadarForRobotTurn(true);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        setBodyColor(new Color(235, 232, 240));
        setGunColor(new Color(212, 175, 55));
        setRadarColor(new Color(138, 61, 92));
        setScanColor(new Color(200, 100, 150));
        setBulletColor(new Color(212, 175, 55));

        while (true) {
            escolherAlvo();
            controlarRadar();
            mover();
            mirarEAtirar();
            enviarStatus();
            execute();
        }
    }

    private Alvo alvo() {
        return alvoAtual == null ? null : inimigos.get(alvoAtual);
    }

    private void escolherAlvo() {
        long agora = getTime();
        Alvo melhor = null;
        double melhorScore = Double.MAX_VALUE;

        for (Alvo a : inimigos.values()) {
            if (agora - a.tempo > IDADE_MAX) {
                continue;
            }
            double score = Point2D.distance(getX(), getY(), a.x, a.y) + a.energia * 1.5;
            if (a.nome.equals(alvoAtual)) {
                score -= 80;
            }
            if (a.nome.equals(alvoDoAliado)) {
                score -= 140;
            }
            if (score < melhorScore) {
                melhorScore = score;
                melhor = a;
            }
        }
        alvoAtual = melhor == null ? null : melhor.nome;
    }

    private void controlarRadar() {
        Alvo a = alvo();
        int inimigosVivos = getOthers() - aliadosVivos.size();

        if (a != null && inimigosVivos <= 1 && getTime() - a.tempo <= 3) {
            double ang = Math.atan2(a.x - getX(), a.y - getY());
            double giro = Utils.normalRelativeAngle(ang - getRadarHeadingRadians());
            setTurnRadarRightRadians(giro * 2);
        } else {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        }
    }

    private void mover() {
        long agora = getTime();
        double x = getX(), y = getY();
        double fx = 0, fy = 0;
        Alvo a = alvo();

        if (a != null) {
            double ang = Math.atan2(a.x - x, a.y - y);
            double d = Point2D.distance(x, y, a.x, a.y);

            if (agora >= proximaTroca) {
                orbita = -orbita;
                proximaTroca = agora + 25 + (long) (Math.random() * 40);
            }

            double tang = ang + orbita * Math.PI / 2;
            fx += Math.sin(tang);
            fy += Math.cos(tang);

            double radial = Math.max(-1, Math.min(1, (d - DIST_PREFERIDA) / DIST_PREFERIDA));
            fx += Math.sin(ang) * radial;
            fy += Math.cos(ang) * radial;
        } else {
            fx += (getBattleFieldWidth() / 2 - x) / getBattleFieldWidth();
            fy += (getBattleFieldHeight() / 2 - y) / getBattleFieldHeight();
        }

        Aliado parceiro = null;
        for (Aliado al : aliados.values()) {
            if (agora - al.tempo <= IDADE_ALIADO) {
                parceiro = al;
            }
        }
        if (a != null && parceiro != null) {
            double ax = a.x - parceiro.x, ay = a.y - parceiro.y;
            double n = Math.hypot(ax, ay);
            if (n > 1) {
                double px = a.x + ax / n * DIST_PREFERIDA;
                double py = a.y + ay / n * DIST_PREFERIDA;
                double ddx = px - x, ddy = py - y;
                double dd = Math.hypot(ddx, ddy);
                if (dd > 1) {
                    double peso = 0.9 * Math.min(1, dd / 200);
                    fx += ddx / dd * peso;
                    fy += ddy / dd * peso;
                }
            }
        }

        for (Alvo o : inimigos.values()) {
            if (o != a && agora - o.tempo <= IDADE_MAX) {
                double[] r = repulsao(x, y, o.x, o.y, 160, 1.5);
                fx += r[0];
                fy += r[1];
            }
        }
        for (Aliado al : aliados.values()) {
            if (agora - al.tempo <= IDADE_ALIADO) {
                double[] r = repulsao(x, y, al.x, al.y, 150, 2.5);
                fx += r[0];
                fy += r[1];
            }
        }

        double desejado = Math.atan2(fx, fy);
        desejado = suavizarParede(x, y, desejado, orbita);
        irPara(desejado);
    }

    private double[] repulsao(double x, double y, double ox, double oy, double raio, double peso) {
        double dx = x - ox, dy = y - oy;
        double d = Math.hypot(dx, dy);
        if (d >= raio || d < 0.001) {
            return new double[] {0, 0};
        }
        double mag = (raio - d) / raio * peso;
        return new double[] {dx / d * mag, dy / d * mag};
    }

    private double suavizarParede(double x, double y, double ang, int dir) {
        for (int i = 0; i < 60
                && !campo.contains(x + Math.sin(ang) * 140, y + Math.cos(ang) * 140); i++) {
            ang += dir * 0.07;
        }
        return ang;
    }

    private void irPara(double angulo) {
        double giro = Utils.normalRelativeAngle(angulo - getHeadingRadians());
        int sentido = 1;
        if (Math.abs(giro) > Math.PI / 2) {
            giro = Utils.normalRelativeAngle(giro + Math.PI);
            sentido = -1;
        }
        setTurnRightRadians(giro);
        setMaxVelocity(Math.max(2, 8 - 4 * Math.abs(giro)));
        setAhead(100 * sentido);
    }

    private void mirarEAtirar() {
        Alvo a = alvo();
        if (a == null) {
            return;
        }

        long agora = getTime();
        double dist = Point2D.distance(getX(), getY(), a.x, a.y);
        double potencia = calcularPotencia(a, dist);
        double vBala = 20 - 3 * potencia;

        long dt = agora - a.tempo;
        double vx = Math.sin(a.rumo) * a.velocidade;
        double vy = Math.cos(a.rumo) * a.velocidade;
        double px = limitarX(a.x + vx * dt);
        double py = limitarY(a.y + vy * dt);

        double tx = px, ty = py;
        int t = 0;
        do {
            t++;
            tx = limitarX(px + vx * t);
            ty = limitarY(py + vy * t);
        } while (Point2D.distance(getX(), getY(), tx, ty) > vBala * t && t < 120);

        double ang = Math.atan2(tx - getX(), ty - getY());
        double giro = Utils.normalRelativeAngle(ang - getGunHeadingRadians());
        setTurnGunRightRadians(giro);

        double tolerancia = Math.atan(18.0 / Math.max(dist, 1));

        if (getGunHeat() == 0
                && getEnergy() > 0.2
                && dt <= 8
                && Math.abs(giro) <= tolerancia
                && linhaLivre(ang, dist)) {
            setFire(potencia);
        }
    }

    private double calcularPotencia(Alvo a, double dist) {
        double p = Math.max(0.5, Math.min(3.0, 500.0 / dist));

        if (a.energia < 16) {
            double matar = a.energia <= 4 ? a.energia / 4 : (a.energia + 2) / 6;
            p = Math.min(p, matar + 0.05);
        }
        if (getEnergy() < 20) {
            p = Math.min(p, getEnergy() / 8);
        }
        return Math.max(0.1, Math.min(2.0, p));
    }

    private boolean linhaLivre(double ang, double distAlvo) {
        double sx = Math.sin(ang), cy = Math.cos(ang);
        for (Aliado al : aliados.values()) {
            if (getTime() - al.tempo > IDADE_ALIADO) {
                continue;
            }
            double dx = al.x - getX(), dy = al.y - getY();
            double ao_longo = dx * sx + dy * cy;
            double lateral = Math.abs(dx * cy - dy * sx);
            if (ao_longo > -MARGEM_ALIADO && ao_longo < distAlvo + MARGEM_ALIADO
                    && lateral < MARGEM_ALIADO) {
                return false;
            }
        }
        return true;
    }

    private double limitarX(double x) {
        return Math.max(18, Math.min(getBattleFieldWidth() - 18, x));
    }

    private double limitarY(double y) {
        return Math.max(18, Math.min(getBattleFieldHeight() - 18, y));
    }

    private void enviarStatus() {
        String msg = "A|" + getX() + "|" + getY() + "|"
                + (alvoAtual == null ? "-" : alvoAtual) + scansDoTurno;
        scansDoTurno.setLength(0);
        try {
            broadcastMessage(msg);
        } catch (java.io.IOException ex) {
        }
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        if (!(e.getMessage() instanceof String)) {
            return;
        }
        try {
            for (String parte : ((String) e.getMessage()).split(";")) {
                String[] c = parte.split("\\|");
                if (c[0].equals("A") && c.length >= 4) {
                    Aliado al = aliados.get(e.getSender());
                    if (al == null) {
                        al = new Aliado();
                        aliados.put(e.getSender(), al);
                    }
                    al.x = Double.parseDouble(c[1]);
                    al.y = Double.parseDouble(c[2]);
                    al.tempo = getTime();
                    alvoDoAliado = "-".equals(c[3]) ? null : c[3];
                } else if (c[0].equals("E") && c.length >= 7) {
                    registrarInimigo(c[1],
                            Double.parseDouble(c[2]), Double.parseDouble(c[3]),
                            Double.parseDouble(c[4]), Double.parseDouble(c[5]),
                            Double.parseDouble(c[6]), getTime() - 1);
                }
            }
        } catch (NumberFormatException ex) {
        }
    }

    private void registrarInimigo(String nome, double x, double y, double energia,
                                  double rumo, double vel, long tempo) {
        if (isTeammate(nome) || mortos.contains(nome)) {
            return;
        }
        Alvo a = inimigos.get(nome);
        if (a == null) {
            a = new Alvo();
            a.nome = nome;
            inimigos.put(nome, a);
        } else if (a.tempo >= tempo) {
            return;
        } else if (nome.equals(alvoAtual)
                && tempo - a.tempo <= 2
                && getTime() - ultimoAcerto > 2) {
            double queda = a.energia - energia;
            if (queda > 0.09 && queda < 3.01) {
                orbita = -orbita;
            }
        }
        a.x = x;
        a.y = y;
        a.energia = energia;
        a.rumo = rumo;
        a.velocidade = vel;
        a.tempo = tempo;
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        double ang = getHeadingRadians() + e.getBearingRadians();
        double x = getX() + Math.sin(ang) * e.getDistance();
        double y = getY() + Math.cos(ang) * e.getDistance();

        if (isTeammate(e.getName())) {
            Aliado al = aliados.get(e.getName());
            if (al == null) {
                al = new Aliado();
                aliados.put(e.getName(), al);
            }
            al.x = x;
            al.y = y;
            al.tempo = getTime();
            return;
        }

        registrarInimigo(e.getName(), x, y, e.getEnergy(),
                e.getHeadingRadians(), e.getVelocity(), getTime());

        scansDoTurno.append(";E|").append(e.getName())
                .append('|').append(x).append('|').append(y)
                .append('|').append(e.getEnergy())
                .append('|').append(e.getHeadingRadians())
                .append('|').append(e.getVelocity());
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        String n = e.getName();
        mortos.add(n);
        inimigos.remove(n);
        aliados.remove(n);
        aliadosVivos.remove(n);
        if (n.equals(alvoAtual)) {
            alvoAtual = null;
        }
        if (n.equals(alvoDoAliado)) {
            alvoDoAliado = null;
        }
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        ultimoAcerto = getTime();
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        if (!isTeammate(e.getName())) {
            orbita = -orbita;
        }
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        orbita = -orbita;
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        orbita = -orbita;
        setBack(40);
    }

    @Override
    public void onWin(WinEvent e) {
        for (int i = 0; i < 50; i++) {
            turnRight(30);
            turnLeft(30);
        }
    }
}
