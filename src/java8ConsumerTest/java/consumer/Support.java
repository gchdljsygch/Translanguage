package consumer;

public final class Support {
    private Support() { }
    public static int jdk() {
        String version=System.getProperty("java.specification.version");
        return Integer.parseInt(version.startsWith("1.")?version.substring(2):version);
    }
    public static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder();
        for(byte b:bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    public static String repeat(String value,int count) {
        StringBuilder result=new StringBuilder();for(int i=0;i<count;i++) result.append(value);return result.toString();
    }
}
